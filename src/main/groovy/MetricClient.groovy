package com.allthingsmonitoring.utils

import org.slf4j.*
import groovy.util.logging.Slf4j
import ch.qos.logback.classic.*
import static ch.qos.logback.classic.Level.*
import org.codehaus.groovy.runtime.StackTraceUtils
import groovy.time.*

import net.razorvine.pickle.*


@Slf4j
class MetricClient {
  String graphite_host, protocol, prefix
  int graphite_port
  int socketTimeOut = 10000

  private LinkedList mBuffer = []
  private LinkedList mBufferPickle = []

  MetricClient(String graphite_host = 'localhost', int graphite_port = 2003, String protocol = 'tcp', String prefix = null) {
    this.graphite_host = graphite_host
    this.graphite_port = graphite_port
    this.protocol = protocol?.toLowerCase()
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


  /**
   * Send metrics using Text format to the Graphite server
   *
   * @param metrics List of metric strings
   */
  void send2Graphite(ArrayList metrics, Boolean useBuffer=true) {
    if (!metrics) { return }

    Date timeStart = new Date()
    int sentCount = 0
    def socket

    log.debug "Sending Metrics to Graphite (${graphite_host}:${graphite_port}) using '${protocol}' (useBuffer: ${useBuffer})"

    try {
      if (protocol == 'tcp'){
        socket = new Socket(graphite_host, graphite_port)
        socket.setSoTimeout(socketTimeOut)
      } else {
        socket = new DatagramSocket()
        socket.setSoTimeout(socketTimeOut)
      }

    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Socket exception: ${getStackTrace(e)}"

      if (useBuffer) {
        mBuffer.addAll(prefix ? metrics.collect { "${prefix}.${it}" } : metrics)
        log.warn "Added ${metrics?.size()} Metrics in the Buffer (mBuffer: ${mBuffer.size()})"
      }

      return
    }

    // Send buffered metrics first
    if(mBuffer && useBuffer) {
      log.info "Sending ${mBuffer.size()} Buffered Metrics"

      while (mBuffer.size() > 0) {
        String msg = mBuffer.poll()
        log.trace "Metric: ${msg} (mBuffer: ${mBuffer.size()})"

        try {
          if (protocol == 'tcp') {
            Writer writer = new OutputStreamWriter(socket?.getOutputStream())
            writer.write(msg)
            writer.flush()
          } else {
            byte[] bytes = msg.getBytes()
            InetAddress addr = InetAddress.getByName(graphite_host)
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, addr, graphite_port)
            socket?.send(packet)
          }
          sentCount++

        } catch(Exception e) {
          StackTraceUtils.deepSanitize(e)
          log.warn "Sending Buffered Metric: ${getStackTrace(e)}"

          mBuffer << msg
          log.warn "Buffered Metric added to the Buffer (mBuffer: ${mBuffer.size()})"
        }
      }
    }

    log.info "Sending ${metrics?.size()} Metrics"
    metrics.each { String it ->
      String msg = prefix ? "${prefix}.${it}" : it

      try {
        if (protocol == 'tcp') {
          Writer writer = new OutputStreamWriter(socket?.getOutputStream())
          writer.write(msg)
          writer.flush()
        } else {
          byte[] bytes = msg.getBytes()
          InetAddress addr = InetAddress.getByName(graphite_host)
          DatagramPacket packet = new DatagramPacket(bytes, bytes.length, addr, graphite_port)
          socket?.send(packet)
        }
        sentCount++

      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.warn "Sending Metric: ${getStackTrace(e)}"

        if (useBuffer) {
          mBuffer << msg
          log.warn "Metric added to the Buffer (mBuffer: ${mBuffer.size()})"
        }
      }
    }

    socket?.close()

    Date timeEnd = new Date()
    log.info "Finished sending ${sentCount} Metrics (mBuffer: ${mBuffer.size()}) to Graphite in ${TimeCategory.minus(timeEnd, timeStart)}"
  }



  /**
   * Send metrics using Pickle format to the Graphite server
   *
   * @param metrics List of metric strings
   */
  void send2GraphitePickle(ArrayList metrics, Boolean useBuffer=true) {
    if (!metrics) { return }

    Date timeStart = new Date()
    int sentCount = 0
    Socket socket
    ArrayList picklePkgs = []
    ArrayList pickleBufferPkgs = []

    log.debug "Sending Metrics to Graphite (${graphite_host}:${graphite_port}) using 'tcp' (useBuffer: ${useBuffer})"

    try {
      socket = new Socket(graphite_host, graphite_port)
      socket.setSoTimeout(socketTimeOut)

    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Socket exception: ${getStackTrace(e)}"

      if (useBuffer) {
        mBuffer.addAll(prefix ? metrics.collect { "${prefix}.${it}" } : metrics)
        log.warn "Added ${metrics?.size()} Metrics in the Buffer (mBuffer: ${mBuffer.size()})"
      }

      return
    }

    // Send buffered metrics first
    if(mBuffer && useBuffer) {
      log.info "Sending ${mBuffer?.size()} Buffered Metrics"

      try {
        log.info "Generating Pickle Packages for ${mBuffer?.size()} Buffered Metrics"
        pickleBufferPkgs = generatePicklerPkgs(mBuffer.toList())
        mBuffer = []

      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.error "Generating Pickle: ${getStackTrace(e)}"
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
          log.warn "Sending Metric (Pickle): ${getStackTrace(e)}"

          mBufferPickle << pkg
          log.warn "Metric added to the PickleBuffer (mBufferPickle: ${mBufferPickle.size()})"
        }
      }
    }

    // Send Buffered Pickler Package Metrics first
    if (mBufferPickle && useBuffer) {
      log.info "Sending ${mBufferPickle?.size()} Buffered Pickle Package Metrics"

      // Send metrics
      while (mBufferPickle.size() > 0) {
        byte[] pkg = mBufferPickle.poll()
        log.trace "Metric: ${pkg} (mBufferPickle: ${mBufferPickle.size()})"

        try {
          DataOutputStream dOut = new DataOutputStream(socket?.getOutputStream())
          dOut.writeInt(pkg.size())
          dOut.write(pkg)
          dOut.flush()
          sentCount++

        } catch(Exception e) {
          StackTraceUtils.deepSanitize(e)
          log.warn "Sending Metric (Pickle): ${getStackTrace(e)}"

          mBufferPickle << pkg
          log.warn "Metric added to the PickleBuffer (mBufferPickle: ${mBufferPickle.size()})"
        }
      }
    }


    try {
      log.info "Generating Pickle Packages for ${metrics?.size()} Metrics"
      picklePkgs = prefix ? generatePicklerPkgs(metrics.collect { "${prefix}.${it}" }) : generatePicklerPkgs(metrics)

    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Generating Pickle: ${getStackTrace(e)}"

      if (useBuffer) {
        mBuffer.addAll(prefix ? metrics.collect { "${prefix}.${it}" } : metrics)
        log.warn "Added ${metrics?.size()} Metrics in the Buffer (mBuffer: ${mBuffer.size()})"
      }
    }

    // Send metrics
    picklePkgs.each { byte[] pkg ->
      try {
        DataOutputStream dOut = new DataOutputStream(socket?.getOutputStream())
        dOut.writeInt(pkg.size())
        dOut.write(pkg)
        dOut.flush()
        sentCount++

      } catch(Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.warn "Sending Metric (Pickle): ${getStackTrace(e)}"

        if (useBuffer) {
          mBufferPickle << pkg
          log.warn "Metric added to the PickleBuffer (mBufferPickle: ${mBufferPickle.size()})"
        }
      }
    }
    socket?.close()

    Date timeEnd = new Date()
    log.info "Finished sending ${sentCount} Metric Pickler Packages (mBuffer: ${mBuffer.size()} / mBufferPickle: ${mBufferPickle.size()}) to Graphite in ${TimeCategory.minus(timeEnd, timeStart)}"
  }


  /*
   * Generate a list of Pickler packages that not reache Carbon maxLength 1048576:Bytes = 1:MB
   *
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
          log.debug "Reached Pickler Package MaxLength: ${pkg?.size()}"
          pkgs << pkg
          dataTemp = []
        }
      }
      mCount++
    }

    byte[] pkg = p.dumps(dataTemp)
    pkgs << pkg
    log.debug "Pickler Smallest Package: ${pkg?.size()}"

    Date timeEnd = new Date()
    log.info "Created ${pkgs?.size()} Pickler Packages for ${metrics?.size()} Metrics in ${TimeCategory.minus(timeEnd, timeStart)}"
    return pkgs
  }
}
