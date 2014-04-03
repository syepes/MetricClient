import groovy.util.GroovyTestCase
import groovy.mock.interceptor.StubFor
import com.allthingsmonitoring.utils.*

class MetricClientMethodTests extends GroovyTestCase {
  def resultText
  def subject
  def mockRNG

  void setUp() {
    subject = new MetricClient()
    subject.metaClass.doSend = { resultText = it }

    mockRNG = new Random()
    mockRNG.metaClass.nextDouble = { 0 } // not so random!
    subject.RNG = mockRNG
  }

  // test initialization
  void test_initialization_without_parameters() {
    def s = new MetricClient()
    assert s.prefix == null
    assert s.host == 'localhost'
    assert s.port == 8125
    assert s.protocol == 'udp'
  }

  void test_initialization_with_parameters() {
    def s = new MetricClient('stats', 'localhost', 3000, 'udp')
    assert s.prefix == 'stats'
    assert s.host == 'localhost'
    assert s.port == 3000
    assert s.protocol == 'udp'
  }

  // timing() tests
  void test_timing_with_full_samplerate() {
    subject.timing('test.timing', 100)
    assert resultText == ['test.timing:100|ms']
  }

  void test_timing_with_partial_samplerate() {
    subject.timing('test.timing', 100, 0.5)
    assert resultText == ['test.timing:100|ms|@0,500000']
  }

  void test_timing_with_multipe_stats_and_full_samplerate() {
    subject.timing( ['test.timing', 'test2.timing'], 100)
    assert resultText == ['test.timing:100|ms', 'test2.timing:100|ms']
  }

  // increment() tests
  void test_increment_with_single_stat_and_no_arguments() {
    subject.incr('test.incr')
    assert resultText == ['test.incr:1|c']
  }

  void test_increment_with_single_stat_and_explicit_value() {
    subject.incr('test.incr', 100)
    assert resultText == ['test.incr:100|c']
  }

  void test_increment_with_single_stat_and_explicit_value_and_partial_samplerate() {
    subject.incr('test.incr', 100, 0.5)
    assert resultText == ['test.incr:100|c|@0,500000']
  }

  void test_increment_with_multiple_stats_and_no_arguments() {
    subject.incr( ['test.incr', 'test2.incr'] )
    assert resultText == ['test.incr:1|c', 'test2.incr:1|c']
  }

  void test_increment_with_multiple_stats_and_explicit_value() {
    subject.incr( ['test.incr', 'test2.incr'], 100 )
    assert resultText == ['test.incr:100|c', 'test2.incr:100|c']
  }

  void test_increment_with_multiple_stats_and_explicit_value_and_partial_samplerate() {
    subject.incr( ['test.incr', 'test2.incr'], 100, 0.5 )
    assert resultText == ['test.incr:100|c|@0,500000', 'test2.incr:100|c|@0,500000']
  }

  // decrement() tests
  void test_decrement_with_single_stat_and_no_arguments() {
    subject.decr('test.decr')
    assert resultText == ['test.decr:-1|c']
  }

  void test_decrement_with_single_stat_and_explicit_value() {
    subject.decr('test.decr', -100)
    assert resultText == ['test.decr:-100|c']
  }

  void test_decrement_with_single_stat_and_explicit_value_and_partial_samplerate() {
    subject.decr('test.decr', -100, 0.5)
    assert resultText == ['test.decr:-100|c|@0,500000']
  }

  void test_decrement_with_multiple_stats_and_no_arguments() {
    subject.decr( ['test.decr', 'test2.decr'] )
    assert resultText == ['test.decr:-1|c', 'test2.decr:-1|c']
  }

  void test_decrement_with_multiple_stats_and_explicit_value() {
    subject.decr( ['test.decr', 'test2.decr'], -100 )
    assert resultText == ['test.decr:-100|c', 'test2.decr:-100|c']
  }

  void test_decrement_with_multiple_stats_and_explicit_value_and_partial_samplerate() {
    subject.decr( ['test.decr', 'test2.decr'], -100, 0.5 )
    assert resultText == ['test.decr:-100|c|@0,500000', 'test2.decr:-100|c|@0,500000']
  }
}
