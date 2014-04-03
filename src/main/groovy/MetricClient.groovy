package com.allthingsmonitoring.utils

class MetricClient {
  def prefix, host, port, inetAddr, protocol
  def socket
  Random RNG = new Random()
  final int sendTimeOut = 1000 // block for no more than 1 seconds

  MetricClient( String prefix = null, String host = 'localhost', int port = 8125, String protocol = 'udp') {
    this.prefix = prefix
    this.host = host
    this.port = port
    this.protocol = protocol
    this.inetAddr = InetAddress.getByName( this.host )
    if (protocol == 'udp'){
      this.socket = new DatagramSocket()
      this.socket.setSoTimeout(sendTimeOut)
    } else if (protocol == 'tcp'){
      this.socket = new Socket(this.host, this.port)
      this.socket.setSoTimeout(sendTimeOut)
    }
  }

  /**
   * gauges
   *
   * @param metrics String or list of strings of metric names
   * @param time time to log in ms
   * @param samplerate a double specifying a sample rate, default = 1 (100%)
   */
  void gauge( metrics, time, sampleRate = 1 ) {
    def _stats = [metrics].flatten()
    sendStats( _stats.collect { String.format("%s:%d|g", it, time) }, sampleRate )
  }


  /**
   * timer
   *
   * @param metric String or list of metric names
   * @param c Closure that will be execute and its execution time will be used as the timer
   */
  void timer(metric, Closure c){
     Date timeStart = new Date()
     c.call()
     Date timeEnd = new Date()
     timing(metric,(timeEnd.time - timeStart.time))
  }

  /**
   * timing
   *
   * @param metrics String or list of strings of metric names
   * @param time time to log in ms
   * @param samplerate a double specifying a sample rate, default = 1 (100%)
   */
  void timing( metrics, time, sampleRate = 1 ) {
    def _stats = [metrics].flatten()
    sendStats( _stats.collect { String.format("%s:%d|ms", it, time) }, sampleRate )
  }

  /**
   * incr/decr
   *
   * @param metrics String or list of strings of metric names
   * @param delta Increment step
   * @param samplerate a double specifying a sample rate, default = 1 (100%)
   */
  void incr( metrics, delta = 1 , sampleRate = 1 ) { updateCounter( metrics, delta, sampleRate ) }
  void decr( metrics, delta = -1, sampleRate = 1 ) { updateCounter( metrics, delta, sampleRate ) }

  void updateCounter( metrics, delta = 1, sampleRate = 1 ) {
    def _stats = [metrics].flatten()
    sendStats( _stats.collect { "${it}:${delta}|c" }, sampleRate )
  }



  /**
   * Graphite
   *
   * @param data a string or array of strings
   * @param ts Add to the metric the specified Epoch  
   */
  void graphite( data, long ts = 0 ) {
    def _data = [data].flatten()

    if (ts) {
      ArrayList dataWithTS = []
      _data.each { d ->
        dataWithTS << String.format("%s %d", d, ts)
      }
      _data = dataWithTS
    }
    doSend( _data )
  }


  /**
   * sendStats
   *
   * @param data a string or array of strings
   * @param samplerate a double specifying a sample rate, default = 1 (100%)
   */
  private void sendStats( data, sampleRate = 1 ) {
    def _data = [data].flatten()

    if (sampleRate < 1.0) {
      ArrayList sampledData = []
      _data.each { d ->
        if (RNG.nextDouble() <= sampleRate) {
          //sampledData.add( String.format("%s|@%f", d, sampleRate) )
          sampledData << String.format("%s|@%f", d, sampleRate)
        }
      }
      _data = sampledData
    }
    doSend( _data )
  }

  /**
   * doSend
   *
   * @param data a string or array of strings that will be sent to statsd
   */
  private void doSend( data ) {
    def _data = [data].flatten()
    if(this.protocol?.toLowerCase() == 'tcp'){
      _data.each { d ->
        StringBuilder msg = new StringBuilder()

        if (this.prefix){ msg << this.prefix +'.'+ d }else{ msg << d }
        msg << '\n'

        Writer writer = new OutputStreamWriter(this.socket.getOutputStream())
        writer.write(msg.toString())
        writer.flush()
      }
    } else {
      _data.each { d ->
        StringBuilder msg = new StringBuilder()

        if (this.prefix){ msg << this.prefix +'.'+ d }else{ msg << d }
        msg << '\n'

        byte[] bytes = msg.toString().getBytes()
        this.socket.send( new DatagramPacket(bytes, bytes.length, this.inetAddr, this.port) )
      }
    }
  }
}
