package com.allthingsmonitoring.utils

import org.slf4j.*
import groovy.util.logging.Slf4j
import ch.qos.logback.classic.*
import static ch.qos.logback.classic.Level.*
import org.codehaus.groovy.runtime.StackTraceUtils
import groovy.time.*

import groovy.transform.WithReadLock
import groovy.transform.WithWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.ReadWriteLock

import java.util.zip.GZIPOutputStream
import java.net.URL
import java.net.HttpURLConnection

import net.razorvine.pickle.*


@Slf4j
class MetricClient {
  String server_host, server_auth, protocol, prefix
  int server_port,socketTimeOut,http_connectTimeout,http_readTimeout,maxTries

  private final ReadWriteLock mBufferLock = new ReentrantReadWriteLock()
  private final ReadWriteLock mBufferPickleLock = new ReentrantReadWriteLock()

  private final LinkedList mBuffer = []
  private final LinkedList mBufferPickle = []


  MetricClient(String server_host, int server_port, String protocol, String prefix, String server_auth) {
     MetricClient(server_host, server_port, protocol, [socket_timeout_ms: 10000, http_connect_timeout_ms: 5000, http_read_timeout_ms: 30000, max_tries: 60], prefix, server_auth)
  }

  MetricClient(String server_host = 'localhost', int server_port = 2003, String protocol = 'tcp', LinkedHashMap parms = [socket_timeout_ms: 10000, http_connect_timeout_ms: 5000, http_read_timeout_ms: 30000, max_tries: 60], String prefix = null, String server_auth = '') {
    this.server_host = server_host
    this.server_port = server_port
    this.server_auth = server_auth
    this.protocol = protocol?.toLowerCase()
    this.socketTimeOut = parms?.socket_timeout_ms
    this.http_connectTimeout = parms?.http_connect_timeout_ms
    this.http_readTimeout = parms?.http_read_timeout_ms
    this.maxTries = parms?.max_tries
    this.prefix = prefix
  }


  // Gets the StackTrace and returns a string
  String getStackTrace(Throwable t) {
    StringWriter sw = new StringWriter()
    PrintWriter pw = new PrintWriter(sw, true)
    t.printStackTrace(pw)
    pw.flush()
    sw.flush()
    return sw.toString()
  }


  @WithReadLock('mBufferLock')
  LinkedList getBuffer() {
    mBuffer
  }
  @WithReadLock('mBufferLock')
  int getBufferSize() {
    mBuffer?.size()
  }
  @WithWriteLock('mBufferLock')
  String pollBufferItem() {
    mBuffer.poll()
  }
  @WithWriteLock('mBufferLock')
  void addBufferItem(String item) {
    mBuffer << item
  }
  @WithWriteLock('mBufferLock')
  void addBufferItems(ArrayList items) {
    mBuffer.addAll(items)
  }
  @WithWriteLock('mBufferLock')
  void clearBuffer() {
    mBuffer.clear()
  }


  @WithReadLock('mBufferPickleLock')
  LinkedList getBufferPickle() {
    mBufferPickle
  }
  @WithReadLock('mBufferPickleLock')
  int getBufferPickleSize() {
    mBufferPickle?.size()
  }
  @WithWriteLock('mBufferPickleLock')
  byte[] pollBufferPickleItem() {
    mBufferPickle.poll()
  }
  @WithWriteLock('mBufferPickleLock')
  void addBufferPickleItem(byte[] item) {
    mBufferPickle << item
  }
  @WithWriteLock('mBufferPickleLock')
  void addBufferPickleItems(ArrayList items) {
    mBufferPickle.addAll(items)
  }
  @WithWriteLock('mBufferPickleLock')
  void clearBufferPickle() {
    mBufferPickle.clear()
  }



  /**
   * Send metrics using Text format to the Graphite server
   *
   * @param metrics List of metric strings
   * @param useBuffer Boolean Toggle to enable Buffering of failures
   */
  void send2Graphite(ArrayList metrics, Boolean useBuffer=true) {
    if (!metrics) { return }

    Date timeStart = new Date()
    int sentCount = 0
    def socket

    log.debug "Sending Metrics to Graphite (${server_host}:${server_port}) using '${protocol}' (useBuffer: ${useBuffer})"

    try {
      if (protocol == 'tcp') {
        socket = new Socket(server_host, server_port)
        socket.setSoTimeout(socketTimeOut)
      } else {
        socket = new DatagramSocket()
        socket.setSoTimeout(socketTimeOut)
      }

    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Socket exception: ${e?.message}"
      log.debug "Socket exception: ${getStackTrace(e)}"

      if (useBuffer) {
        addBufferItems(prefix ? metrics.collect { "${prefix}.${it}" } : metrics)
        log.warn "Added ${metrics?.size()} Metrics in the Buffer (mBuffer: ${getBufferSize()})"
      }

      return
    }

    // Send buffered metrics first
    if (getBufferSize() && useBuffer) {
      log.info "Sending ${getBufferSize()} Graphite Buffered Metrics"
      int sendTries = 0

      // Send metrics
      while (getBufferSize() > 0 && sendTries <= maxTries) {
        String msg = pollBufferItem()
        log.trace "Metric: ${msg} (mBuffer: ${getBufferSize()})"

        try {
          if (protocol == 'tcp') {
            Writer writer = new OutputStreamWriter(socket?.getOutputStream())
            writer.write(msg)
            writer.flush()
          } else {
            byte[] bytes = msg.getBytes()
            InetAddress addr = InetAddress.getByName(server_host)
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, addr, server_port)
            socket?.send(packet)
          }
          sentCount++

        } catch(Exception e) {
          StackTraceUtils.deepSanitize(e)
          log.warn "Sending Buffered Metric: ${e?.message}"
          log.debug "Sending Buffered Metric: ${getStackTrace(e)}"

          sendTries++
          addBufferItem(msg)
          log.warn "Buffered Metric added to the Buffer (mBuffer: ${getBufferSize()})"
        }

        if (sendTries >= maxTries) {
          log.error "Sending Buffered Metric reached its maximum retries (${sendTries} >= ${maxTries})"
        }
      }
    }

    // Send metrics
    log.info "Sending ${metrics?.size()} Graphite Metrics"
    metrics.each { String it ->
      String msg = prefix ? "${prefix}.${it}" : it
      log.trace "Metric: ${msg}"

      try {
        if (protocol == 'tcp') {
          Writer writer = new OutputStreamWriter(socket?.getOutputStream())
          writer.write(msg)
          writer.flush()
        } else {
          byte[] bytes = msg.getBytes()
          InetAddress addr = InetAddress.getByName(server_host)
          DatagramPacket packet = new DatagramPacket(bytes, bytes.length, addr, server_port)
          socket?.send(packet)
        }
        sentCount++

      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.warn "Sending Metric: ${e?.message}"
        log.debug "Sending Metric: ${getStackTrace(e)}"

        if (useBuffer) {
          addBufferItem(msg)
          log.warn "Metric added to the Buffer (mBuffer: ${getBufferSize()})"
        }
      }
    }

    socket?.close()

    log.info "Finished sending ${sentCount} Metrics (mBuffer: ${getBufferSize()}) to Graphite in ${TimeCategory.minus(new Date(), timeStart)}"
  }



  /**
   * Send metrics using Pickle format to the Graphite server
   *
   * @param metrics List of metric strings
   * @param useBuffer Boolean Toggle to enable Buffering of failures
   */
  void send2GraphitePickle(ArrayList metrics, Boolean useBuffer=true) {
    if (!metrics) { return }

    Date timeStart = new Date()
    int sentCount = 0
    Socket socket
    ArrayList picklePkgs = []
    ArrayList pickleBufferPkgs = []

    log.debug "Sending Metrics to Graphite (${server_host}:${server_port}) using 'tcp' (useBuffer: ${useBuffer})"

    try {
      socket = new Socket(server_host, server_port)
      socket.setSoTimeout(socketTimeOut)

    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Socket exception: ${e?.message}"
      log.debug "Socket exception: ${getStackTrace(e)}"

      if (useBuffer) {
        addBufferItems(prefix ? metrics.collect { "${prefix}.${it}" } : metrics)
        log.warn "Added ${metrics?.size()} Metrics in the Buffer (mBuffer: ${getBufferSize()})"
      }

      return
    }

    // Send buffered metrics first
    if (getBufferSize() && useBuffer) {
      log.info "Sending ${getBufferSize()} Graphite Buffered Metrics"

      try {
        log.info "Generating Pickle Packages for ${getBufferSize()} Buffered Metrics"
        pickleBufferPkgs = generatePicklerPkgs(getBuffer().toList())
        clearBuffer()

      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.error "Generating Pickle: ${e?.message}"
        log.debug "Generating Pickle: ${getStackTrace(e)}"
      }

      // Send metrics
      pickleBufferPkgs.each { byte[] pkg ->
        try {
          DataOutputStream dOut = new DataOutputStream(socket?.getOutputStream())
          dOut.writeInt(pkg.size())
          dOut.write(pkg)
          dOut.flush()
          sentCount++

        } catch(Exception e) {
          StackTraceUtils.deepSanitize(e)
          log.warn "Sending Metric (Pickle): ${e?.message}"
          log.debug "Sending Metric (Pickle): ${getStackTrace(e)}"

          addBufferPickleItem(pkg)
          log.warn "Metric added to the PickleBuffer (mBufferPickle: ${getBufferPickleSize()})"
        }
      }
    }

    // Send Buffered Pickler Package Metrics first
    if (getBufferPickleSize() && useBuffer) {
      log.info "Sending ${getBufferPickleSize()} Graphite Buffered Pickle Package Metrics"
      int sendTries = 0

      // Send metrics
      while (getBufferPickleSize() > 0 && sendTries <= maxTries) {
        byte[] pkg = pollBufferPickleItem()
        log.trace "Metric: ${pkg} (mBufferPickle: ${getBufferPickleSize()})"

        try {
          DataOutputStream dOut = new DataOutputStream(socket?.getOutputStream())
          dOut.writeInt(pkg.size())
          dOut.write(pkg)
          dOut.flush()
          sentCount++

        } catch(Exception e) {
          StackTraceUtils.deepSanitize(e)
          log.warn "Sending Metric (Pickle): ${e?.message}"
          log.debug "Sending Metric (Pickle): ${getStackTrace(e)}"

          sendTries++
          addBufferPickleItem(pkg)
          log.warn "Metric added to the PickleBuffer (mBufferPickle: ${getBufferPickleSize()})"
        }

        if (sendTries >= maxTries) {
          log.error "Sending Buffered Metric (Pickle) reached its maximum retries (${sendTries} >= ${maxTries})"
        }
      }
    }


    try {
      log.info "Generating Pickle Packages for ${metrics?.size()} Metrics"
      picklePkgs = prefix ? generatePicklerPkgs(metrics.collect { "${prefix}.${it}" }) : generatePicklerPkgs(metrics)

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Generating Pickle: ${e?.message}"
      log.debug "Generating Pickle: ${getStackTrace(e)}"

      if (useBuffer) {
        addBufferItems(prefix ? metrics.collect { "${prefix}.${it}" } : metrics)
        log.warn "Added ${metrics?.size()} Metrics in the Buffer (mBuffer: ${getBufferSize()})"
      }
    }

    // Send metrics
    log.info "Sending ${picklePkgs.size()} Graphite Metric Pickler Packages (mBuffer: ${getBufferSize()} / mBufferPickle: ${getBufferPickleSize()})"

    picklePkgs.each { byte[] pkg ->
      log.trace "Metric: ${pkg}"

      try {
        DataOutputStream dOut = new DataOutputStream(socket?.getOutputStream())
        dOut.writeInt(pkg.size())
        dOut.write(pkg)
        dOut.flush()
        sentCount++

      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.warn "Sending Metric (Pickle): ${e?.message}"
        log.debug "Sending Metric (Pickle): ${getStackTrace(e)}"

        if (useBuffer) {
          addBufferPickleItem(pkg)
          log.warn "Metric added to the PickleBuffer (mBufferPickle: ${getBufferPickleSize()})"
        }
      }
    }
    socket?.close()

    log.info "Finished sending ${sentCount} Metric Pickler Packages (mBuffer: ${getBufferSize()} / mBufferPickle: ${getBufferPickleSize()}) to Graphite in ${TimeCategory.minus(new Date(), timeStart)}"
  }


  /**
   * Generate a list of Pickler packages that does not reach the Carbon maxLength 1048576:Bytes = 1:MB
   *
   * @param metrics List of metric strings
   * @param maxLength Long maximum package length
   *
   * @return ArrayList of the generated Pickler packages
   */
  ArrayList generatePicklerPkgs(ArrayList metrics, long maxLength=972800) {
    if (!metrics) { return [] }

    Date timeStart = new Date()
    Pickler p = new Pickler(false)
    ArrayList dataTemp = []
    ArrayList pkgs = []

    int mCount = 0
    metrics.each { String m ->
      ArrayList a = m.tokenize()
      dataTemp << [a[0], [a[2]?.toLong(), a[1]?.toFloat()] ] // Metric TS Val

      // TODO: Fix this durty workaround and find a faster way of prechecking the pkg size
      if (mCount >= 400) {
        mCount = 0
        byte[] pkg = p.dumps(dataTemp)
        // Verify that the MaxLength is not reached
        if (pkg?.size() >= maxLength) {
          log.trace "Reached Pickler Package MaxLength: ${pkg?.size()}"
          pkgs << pkg
          dataTemp = []
        }
      }
      mCount++
    }

    byte[] pkg = p.dumps(dataTemp)
    pkgs << pkg
    log.trace "Pickler Smallest Package: ${pkg?.size()}"

    log.info "Created ${pkgs?.size()} Pickler Packages for ${metrics?.size()} Metrics in ${TimeCategory.minus(new Date(), timeStart)}"
    return pkgs
  }



  /**
   * Check InfluxDB Status
   *
   * @return Boolean indicating if the server is available
   */
  Boolean pingInfluxDB() {
    Boolean status = false
    String basicAuth
    String response
    String version
    HttpURLConnection con

    try {
      if (server_auth?.contains(':')) {
        basicAuth = "${server_auth?.split(':')?.getAt(0)}:${server_auth?.split(':')?.getAt(1)}".getBytes().encodeBase64().toString()
      }

      URL url = new URL("http://${server_host}:${server_port}/ping")
      con = url.openConnection()
      con.setRequestProperty('Accept', 'application/json; charset=UTF-8')
      con.setRequestProperty('User-Agent', 'MetricClient')
      if (basicAuth) {
        con.setRequestProperty('Authorization', "Basic ${basicAuth}")
      }
      con.setRequestMethod('GET')
      con.useCaches = false
      con.readTimeout = http_readTimeout // ms
      con.connectTimeout = http_connectTimeout // ms
      con.allowUserInteraction = false

      con.connect()
      response = con?.getResponseMessage()?.trim()
      version = con?.getHeaderField('X-InfluxDB-Version') ?: ''

      if (con?.responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
        // TODO: Improve the ping check when the new ping handler is completed
        status = true
      } else { status = false }

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Ping InfluxDB: ${status ? 'OK' : 'Failed'} ${version} (${response}) : ${e?.message}"
      log.debug "Ping InfluxDB: ${getStackTrace(e)}"
    }

    if (status) {
      log.info "Ping InfluxDB: ${status ? 'OK' : 'Failed'} ${version}"
    } else {
      log.error "Ping InfluxDB: ${status ? 'OK' : 'Failed'} ${version} (${response})"
    }
    return status
  }


  /**
   * Write InfluxDB data using the HTTP API
   *
   * @param data String with the content that should be written to InfluxDB
   * @param parms HashMap URL params sent to the HTTP endpoint
   * @param apiType String indicating the API protocol (line or json)
   * @param compression Boolean Toggle to enable HTTP compression
   */
  private void writeInfluxDB(String data, HashMap parms=[:], String apiType='line', Boolean compression=true) {
    if (!data) { return }
    String basicAuth

    try {
      if (server_auth?.contains(':')) {
        basicAuth = "${server_auth?.split(':')?.getAt(0)}:${server_auth?.split(':')?.getAt(1)}".getBytes().encodeBase64().toString()
      }

      String server_url = "http://${server_host}:${server_port}/write" + (parms ? '?'+ parms.collect { it }.join('&') : '')
      URL url = new URL(server_url)
      HttpURLConnection con = url.openConnection()

      if (basicAuth) {
        con.setRequestProperty('Authorization', "Basic ${basicAuth}")
      }

      if (apiType?.toLowerCase() == 'line') {
        con.setRequestProperty('Content-Type', 'text/plain; charset=UTF-8')
      } else if (apiType?.toLowerCase() == 'json') {
        con.setRequestProperty('Content-Type', 'application/json; charset=UTF-8')
      }
      con.setRequestProperty('Accept', '*/*')
      con.setRequestProperty('User-Agent', 'MetricClient')
      con.setRequestMethod('POST')
      con.doOutput = true
      con.useCaches = false
      con.readTimeout = http_readTimeout // ms
      con.connectTimeout = http_connectTimeout // ms
      con.allowUserInteraction = false

      if (compression) {
        con.setRequestProperty('Content-Encoding', 'gzip')
        con.getOutputStream().write( string2gzip(data) )
      } else {
        OutputStreamWriter osw = new OutputStreamWriter(con.getOutputStream(), 'UTF-8')
        osw.write(data)
        osw.close()
      }

      Integer responseCode = con?.getResponseCode()
      if (responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
        log.debug "Sent InfluxDB Metric ${compression ? 'Compressed ' : ''}(${responseCode})"

        // Ignore Response content
        con.getInputStream().close()
      } else {
        BufferedReader br = new BufferedReader(new InputStreamReader(con?.getErrorStream()))
        String error = "'${url}' (${con?.getHeaderField('X-InfluxDB-Version')} : ${responseCode} : ${con?.getResponseMessage()?.trim()}) - ${br?.readLine()}"
        br?.close()
        throw new Exception("${error}")
      }

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Sending InfluxDB Metric: ${e?.message}"
      log.debug "Sending InfluxDB Metric: ${getStackTrace(e)}"
      throw new Exception('Failed')
    }
  }


  /**
   * Compress String using GZIP
   *
   * @param s String that will be compressed
   *
   * @return byte[] Compressed string
   */
  private byte[] string2gzip(String s) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream()
    OutputStreamWriter osw

    try {
      GZIPOutputStream gzip = new GZIPOutputStream(bos)
      osw = new OutputStreamWriter(gzip, 'UTF-8')
      osw.write(s)
    } catch(Exception e) {
      throw new Exception('Failed to GZIP String')
    } finally {
      osw.close()
    }

    return bos?.toByteArray()
  }


  /**
   * Send metrics using the InfluxDB HTTP API
   *
   * @param metrics String metric in json or line format
   * @param parms HashMap URL params sent to the HTTP endpoint
   * @param apiType String indicating the API protocol (line or json)
   * @param useBuffer Boolean Toggle to enable Buffering of failures
   */
  void send2InfluxDB(String metrics, HashMap parms=[:], String apiType='line', Boolean useBuffer=true) {
    send2InfluxDB([metrics] as ArrayList, parms, apiType, useBuffer)
  }

  /**
   * Send metrics using the InfluxDB HTTP API
   *
   * @param metrics List of metrics in json or line format
   * @param parms HashMap URL params sent to the HTTP endpoint
   * @param apiType String indicating the API protocol (line or json)
   * @param useBuffer Boolean Toggle to enable Buffering of failures
   */
  void send2InfluxDB(ArrayList metrics, HashMap parms=[:], String apiType='line', Boolean useBuffer=true) {
    if (!metrics) { return }

    Date timeStart = new Date()
    int sentCount = 0

    log.debug "Sending Metrics to InfluxDB (${server_host}:${server_port}) using '${protocol}' format: ${apiType} (useBuffer: ${useBuffer})"

    try {
      // Buffer if InfluxDB is not available
      if (!pingInfluxDB()) {
        if (useBuffer) {
          addBufferItems(metrics)
          log.warn "Added ${metrics?.size()} Metrics in the Buffer (mBuffer: ${getBufferSize()})"
        }

        return
      }

    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "InfluxDB Status: ${e?.message}"
      log.debug "InfluxDB Status: ${getStackTrace(e)}"

      if (useBuffer) {
        addBufferItems(metrics)
        log.warn "Added ${metrics?.size()} Metrics in the Buffer (mBuffer: ${getBufferSize()})"
      }

      return
    }

    // Send buffered metrics first
    if (getBufferSize() && useBuffer) {
      log.info "Sending ${getBufferSize()} InfluxDB Buffered Metrics"
      int sendTries = 0

      // Send metrics
      while (getBufferSize() > 0 && sendTries <= maxTries) {
        String msg = pollBufferItem()
        log.trace "Metric: ${msg} (mBuffer: ${getBufferSize()})"

        try {
          if (protocol == 'http-compression') {
            writeInfluxDB(msg, parms, apiType)
          } else if (protocol == 'http') {
            writeInfluxDB(msg, parms, apiType, false)
          } else {
            log.error "Unknown InfluxDB protocol: ${protocol}"
          }
          sentCount++

        } catch(Exception e) {
          StackTraceUtils.deepSanitize(e)
          log.warn "Sending Buffered Metric: ${e?.message}"
          log.debug "Sending Buffered Metric: ${getStackTrace(e)}"

          sendTries++
          addBufferItem(msg)
          log.warn "Buffered Metric added to the Buffer (mBuffer: ${getBufferSize()})"
        }

        if (sendTries >= maxTries) {
          log.error "Sending Buffered Metric reached its maximum retries (${sendTries} >= ${maxTries})"
        }
      }
    }

    // Send metrics
    log.info "Sending ${metrics?.size()} InfluxDB Metrics"
    metrics.each { String msg ->
      log.trace "Metric: ${msg}"

      try {
        if (protocol == 'http-compression') {
          writeInfluxDB(msg, parms, apiType)
        } else if (protocol == 'http') {
          writeInfluxDB(msg, parms, apiType, false)
        } else {
          log.error "Unknown InfluxDB protocol: ${protocol}"
        }
        sentCount++

      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.warn "Sending Metric: ${e?.message}"
        log.debug "Sending Metric: ${getStackTrace(e)}"

        if (useBuffer) {
          addBufferItem(msg)
          log.warn "Metric added to the Buffer (mBuffer: ${getBufferSize()})"
        }
      }
    }

    log.info "Finished sending ${sentCount} Metrics (mBuffer: ${getBufferSize()}) to InfluxDB in ${TimeCategory.minus(new Date(), timeStart)}"
  }
}
