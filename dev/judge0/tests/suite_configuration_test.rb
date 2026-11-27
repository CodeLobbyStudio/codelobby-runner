require "minitest/autorun"
require "minitest/mock"
module Rails
  def self.application; self; end
  def self.config; self; end
  def self.middleware; self; end
  def self.use(*); end
  def self.to_prepare; end
end
require ARGV.fetch(0)

class ConfigurationBase
  def get_metadata; {time: "0.1", "time-wall": "0.2"}; end
  def compile
    @codelobby_build_usage = @observed_usage
    :success
  end
end
class ConfigurationJob < ConfigurationBase
  prepend CodeLobbySuiteReport
  def initialize
    @codelobby_compiling = true
  end
  def box_id; 7; end
  def submission; Struct.new(:is_project, :token).new(true, "11111111-2222-3333-4444-555555555555"); end
end

class SuiteConfigurationTest < Minitest::Test
  Result = Struct.new(:exitstatus) do
    def success?; exitstatus == 0; end
  end

  def capture(job, marker_status)
    stored = []
    redis = Object.new
    redis.define_singleton_method(:eval) { |_, keys:, argv:| stored << JSON.parse(argv.last) }
    helper = lambda do |*args, **options|
      assert_equal ["/usr/bin/sudo", "-n", "/usr/local/ruby-2.7.0/bin/ruby",
                    "/usr/local/bin/read-suite-report.rb", "7", "configuration-error"], args
      ["", Result.new(marker_status)]
    end
    CodeLobbySuiteReport.stub(:redis, redis) do
      Open3.stub(:capture2, helper) { job.send(:get_metadata) }
    end
    stored
  end

  def compile_usage(started, observed)
    job = ConfigurationJob.new
    job.instance_variable_set(:@codelobby_compilation_started, started)
    job.instance_variable_set(:@observed_usage, observed)
    Open3.stub(:capture2, ["", Result.new(2)]) do
      assert_equal :success, job.send(:compile)
    end
    job.instance_variable_get(:@codelobby_build_usage)
  end

  def test_reports_zero_only_when_the_phase_wrapper_observed_no_compiler
    assert_equal({cpuTimeMs: 0.0, wallTimeMs: 0.0}, compile_usage(false, nil))
  end

  def test_does_not_treat_unknown_phase_as_zero_compilation
    assert_nil compile_usage(nil, nil)
  end

  def test_preserves_missing_measurement_if_compiler_did_run
    assert_nil compile_usage(true, nil)
  end

  def test_preserves_observed_compilation_measurement
    assert_equal({cpuTimeMs: 5.0, wallTimeMs: 10.0}, compile_usage(true, {cpuTimeMs: 5.0, wallTimeMs: 10.0}))
  end

  def test_captures_configuration_failure_before_terminal_compilation_status
    stored = capture(ConfigurationJob.new, 0)
    assert_equal true, stored.last["configurationError"]
    assert_equal "MISSING", stored.last["state"]
    assert_equal 100.0, stored.last["compilation"]["cpuTimeMs"]
  end

  def test_missing_marker_does_not_claim_a_configuration_failure
    assert_equal false, capture(ConfigurationJob.new, 2).last["configurationError"]
  end

  def test_helper_failure_cannot_silently_become_a_participant_error
    assert_raises(RuntimeError) { capture(ConfigurationJob.new, 1) }
  end

  def test_runtime_metadata_does_not_read_a_participant_written_marker
    job = ConfigurationJob.new
    job.instance_variable_set(:@codelobby_compiling, false)
    Open3.stub(:capture2, ->(*) { flunk "Runtime must not recapture the marker" }) do
      job.send(:get_metadata)
    end
  end
end
