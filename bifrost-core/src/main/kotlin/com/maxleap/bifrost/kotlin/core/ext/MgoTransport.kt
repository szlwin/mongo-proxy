package com.maxleap.bifrost.kotlin.core.ext

import com.maxleap.bifrost.kotlin.api.Namespace
import com.maxleap.bifrost.kotlin.api.NamespaceFactory
import com.maxleap.bifrost.kotlin.api.impl.DefaultNamespaceFactory
import com.maxleap.bifrost.kotlin.core.BifrostConfig
import com.maxleap.bifrost.kotlin.core.MgoWrapperException
import com.maxleap.bifrost.kotlin.core.TransportListener
import com.maxleap.bifrost.kotlin.core.endpoint.DirectEndpoint
import com.maxleap.bifrost.kotlin.core.impl.EsMonitorListener
import com.maxleap.bifrost.kotlin.core.model.*
import com.maxleap.bifrost.kotlin.core.model.admin.cmd.GetLog
import com.maxleap.bifrost.kotlin.core.model.admin.cmd.HostInfo
import com.maxleap.bifrost.kotlin.core.model.op.OpGetMore
import com.maxleap.bifrost.kotlin.core.model.op.OpQuery
import com.maxleap.bifrost.kotlin.core.utils.Buffered
import com.maxleap.bifrost.kotlin.core.utils.Callback
import com.maxleap.bifrost.kotlin.core.utils.Do
import com.mongodb.MongoClient
import com.mongodb.MongoClientOptions
import com.mongodb.ServerAddress
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetSocket
import org.apache.commons.lang3.RandomUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.bson.Document
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.lang.invoke.MethodHandles
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Created by.
 * User: ben
 * Date: 25/07/2017
 * Time: 6:13 PM
 * Email:benkris1@126.com
 *
 */
class MgoTransport(val endpoint: DirectEndpoint,
                   val netClient: NetClient,
                   val failed: Do? = null
) : Closeable {

  private val isClosed = AtomicBoolean(false)
  private val netSocketWrapperFactory: NetSocketWrapperFactory

  init {
    netSocketWrapperFactory = NetSocketWrapperFactory()
  }

  fun transport(chunk: Chunk) {
    val opRequest = chunk.opRequest
    /**
     * TODO 需要校验那些admin 命令不支持
     *
     */
    try {
      filterCmd(opRequest)?.let { this.endpoint.write(it.toBuffer());return@transport }

      netSocketWrapperFactory.getSocketWrapper(opRequest.nameSpace.databaseName)?.let {
        swapper(opRequest, chunk.swapperBuffer(), it)
      } ?: netSocketWrapperFactory.createSocketWrapper(opRequest.nameSpace.databaseName)
        .compose { netSocketWrapper ->
          run {
            netSocketWrapper.socket()?.let { Future.succeededFuture(netSocketWrapper) }
              ?: netSocketWrapper.connect()
          }
        }
        .map {
          swapper(opRequest, chunk.swapperBuffer(), it)
        }
        .otherwise {
          //failed?.invoke()
          logger.error(ExceptionUtils.getStackTrace(it))
          failResponse(opRequest, it.message ?: "can't connect mgo server for ${opRequest.nameSpace}")
        }
    } catch (throwable: Throwable) {
      //failed?.invoke()
      failResponse(opRequest, throwable.message ?: "can't swapper mgo server for ${opRequest.nameSpace}")
    }

  }

  /**
   * 过滤admin cmd
   */
  private fun filterCmd(request: OpRequest): Buffered? {
    when (request.nameSpace.databaseName) {
      ADMIN_DB -> {
        if (request is OpQuery && request.nameSpace.collectionName.equals("\$cmd")) {
          when {
            request.query.containsKey(GET_LOG_CMD) -> {
              return GetLog(request)
            }
            request.query.containsKey(HOST_INFO) -> {
              return HostInfo(opRequest = request)
            }
            else -> {
              throw MgoWrapperException("not support admin command ${request.nameSpace.collectionName}")
            }
          }
        } else {
          throw MgoWrapperException("not support admin command ${request.nameSpace.collectionName}")
        }
      }
      LOCAL_DB -> {
        throw MgoWrapperException("you have no permission to access the 'local' database ")
      }
    }
    return null
  }

  override fun close() {
    synchronized(this) {
      if (!this.isClosed.getAndSet(true))
        this.netSocketWrapperFactory.close()
    }
  }

  private fun swapper(opRequest: OpRequest, buffer: Buffer, netSocketWrapper: NetSocketWrapper) {
    when (netSocketWrapper.dataSourceStatus) {
      NamespaceStatus.ENABLE -> {
        netSocketWrapper.write(opRequest, buffer)
      }
      NamespaceStatus.READONLY -> {
        when (opRequest) {
          is OpQuery -> netSocketWrapper.write(opRequest, buffer)
          is OpGetMore -> netSocketWrapper.write(opRequest, buffer)
          else -> {
            //TODO 不需要返回
          }
        }
      }
    }
  }

  private fun failResponse(op: OpBase, msg: String): Unit {

    this.endpoint.write(Reply.errorReply(op.msgHeader, 10086, msg).toBuffer())
  }


  inner class NetSocketWrapperFactory : Closeable {
    private val serverSockets = ConcurrentHashMap<String, NetSocketWrapper>()
    private val mgoNamespaceFactory: NamespaceFactory

    init {
      mgoNamespaceFactory = DefaultNamespaceFactory()
    }

    fun createSocketWrapper(collectionName: String): Future<NetSocketWrapper> {
      return AsyncPool.execute(Handler {
        var f = it
        var namespace = mgoNamespaceFactory.loadNamespace(collectionName)
        namespace?.onClusterChange { this.close() }
        val urls = namespace?.getAddressAsString()
        val netSocketWrapper = serverSockets.values
          .filter { it.serverUrls.equals(urls) }
          .firstOrNull()

        netSocketWrapper?.let {
          serverSockets.putIfAbsent(collectionName, it)
          f.complete(it)
        } ?: let {
          val serverAddress = findMaster(namespace.serveAddress())
          val netSocketWrapper_tmp = NetSocketWrapper(collectionName, namespace,
            serverAddress,
            netClient, { this.onClose(it) },
            if (BifrostConfig.monitorEnable()) listOf(EsMonitorListener()) else listOf()
          )
          serverSockets.putIfAbsent(collectionName, netSocketWrapper_tmp)
          netSocketWrapper_tmp
          f.complete(netSocketWrapper_tmp)
        }
      })
    }

    fun getSocketWrapper(collectionName: String): NetSocketWrapper? {
      val netSocketWrapper = serverSockets.get(collectionName)
      if (null != netSocketWrapper && null == netSocketWrapper.socket()) {
        serverSockets.remove(collectionName)
      }
      return netSocketWrapper
    }

    /**
     * doc https://docs.mongodb.com/manual/reference/replica-states/
     */
    private fun findMaster(serverAddress: List<ServerAddress>): ServerAddress {
      var mgoClient: MongoClient? = null
      try {
        mgoClient = MongoClient(serverAddress, MongoClientOptions.builder()
          .serverSelectionTimeout(1000 * 10)
          .connectTimeout(1000 * 5)
          .build()
        )
        val runCommand = mgoClient.getDatabase("admin").runCommand(Document("isMaster", 1))
        val primary = runCommand.getString("primary")
        val isMaster = runCommand.getBoolean("ismaster")
        val msg = runCommand.getString("msg")
        if (null != primary) {
          val name = primary.split(":")
          return@findMaster ServerAddress(name[0], Integer.valueOf(name[1]))
        } else if (StringUtils.equals(msg, "isdbgrid")) {
          val pos = RandomUtils.nextInt(0, serverAddress.size)
          return@findMaster serverAddress[pos]
        } else if (isMaster) {
          return@findMaster serverAddress[0]
        }
      } catch (throwable: Throwable) {
        logger.warn("can't get primary from ${serverAddress},error msg:${throwable.message}", throwable)
      } finally {
        mgoClient?.let {
          it.close()
        }
      }
      throw MgoWrapperException("can't get primary from cluster ${serverAddress}")
    }

    /**
     * NetSocketWrapper close 通知调用
     */
    fun onClose(netSocketWrapper: NetSocketWrapper): Unit {
      try {
        if (netSocketWrapper != null) {
          val keys = serverSockets.entries.filter { it.value == netSocketWrapper }
            .map { it.key }
          keys.forEach {
            serverSockets.remove(it)
          }
        }
      } catch (e: Throwable) {
        logger.error(e.message, e)
      }

    }


    override fun close() {
      logger.info("close  all mgo transport connection:${serverSockets.keys}")
      synchronized(this) {
        serverSockets.forEach({ it.value.close() })
        serverSockets.clear()
      }
    }
  }

  inner class NetSocketWrapper(val collectionName: String, namespace: Namespace, val serverAddress: ServerAddress, val netClient: NetClient, val closed: Callback<NetSocketWrapper>? = null, val transportListeners: List<TransportListener> = arrayListOf()) : Closeable {

    private var socket: NetSocket? = null
    val serverUrls: String
    var dataSourceStatus: NamespaceStatus

    init {
      serverUrls = namespace.getAddressAsString()
      dataSourceStatus = namespace.namespaceStatus()
      namespace.onStatusChange { dataSourceStatus = it }
    }

    fun connect(): Future<NetSocketWrapper> {
      var future = Future.future<NetSocketWrapper>()
      netClient.connect(serverAddress.port, serverAddress.host, {
        if (it.succeeded()) {
          val socket = it.result()
          socket.exceptionHandler { e ->
            run {
              logger.warn(ExceptionUtils.getStackTrace(e))
              transportListeners.forEach {
                it.exception(e)
              }
            }
          }
          socket.closeHandler {
            if (logger.isDebugEnabled) {
              logger.debug("socket wrapper closed.")
            }
            this.socket = null
            closed?.invoke(this)
            transportListeners.forEach {
              it.close()
            }
          }
          socket.handler {
            endpoint.write(it)
            transportListeners.forEach { listener ->
              run {
                listener.transport(it)
              }
            }
          }
          this.socket = socket
          future.complete(this)
          if (logger.isDebugEnabled) {
            logger.debug("initialize socket wrapper success.")
          }
        } else {
          var cause = it.cause()
          logger.error("can't  connect nameSpace ${collectionName} cause:${cause.message}", cause)
          future.fail(cause)
        }
      })
      return future
    }

    fun write(opRequest: OpRequest, buffer: Buffer) {

      socket?.write(buffer) ?: throw MgoWrapperException("can't connect  mgo server for ${collectionName}.")
      transportListeners.forEach {
        it.transportStart(opRequest)
      }
    }

    fun socket(): NetSocket? = socket

    override fun close() {
      synchronized(this) {
        if (this.socket != null) {
          this.socket!!.close()
          this.socket = null
          transportListeners.forEach {
            it.close()
          }
        }
      }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
    private val ADMIN_DB = "admin"
    private val LOCAL_DB = "local"
    private val GET_LOG_CMD = "getLog"
    private val HOST_INFO = "hostInfo"
  }

}