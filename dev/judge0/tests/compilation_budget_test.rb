require "minitest/autorun"
module Rails
  def self.application; self; end
  def self.config; self; end
  def self.to_prepare(&block); end
end
require ARGV.fetch(0)
class PhaseBase
  attr_reader :commands, :events, :box_id
  attr_accessor :fail_compile, :reset_ok, :interpreted
  def initialize
    @commands=[]; @events=[]; @box_id=7; @reset_ok=true
  end
  def compile
    return :success if interpreted
    `isolate --cg -b 7 --run -- /bin/bash compile.sh`
    raise "controlled compiler failure" if fail_compile
    `sudo rm compile.sh`
    :success
  end
  def run
    events << :run
    `isolate --cg -b 7 --run -- /bin/bash run.sh`
  end
  def `(command)
    commands << command
    "captured"
  end
  def system(*args, **options)
    events << [:reset, args]
    reset_ok
  end
  prepend CodeLobbyCompilationBudget
end
class CompilationBudgetTest < Minitest::Test
  def test_marks_only_isolate_compilation_command
    job=PhaseBase.new; job.send(:compile)
    assert job.commands[0].start_with?("CODELOBBY_ISOLATE_PHASE=compile isolate ")
    assert_equal "sudo rm compile.sh", job.commands[1]
  end
  def test_execution_is_not_marked_as_compilation
    job=PhaseBase.new; job.send(:compile); job.send(:run)
    assert_equal "isolate --cg -b 7 --run -- /bin/bash run.sh",job.commands.last
    assert_equal :reset,job.events.first.first
    assert_equal :run,job.events.last
  end
  def test_compiler_failure_restores_phase
    job=PhaseBase.new; job.fail_compile=true
    assert_raises(RuntimeError) { job.send(:compile) }
    job.send(:`,"isolate --version")
    assert_equal "isolate --version",job.commands.last
  end
  def test_interpreted_language_does_not_reset_compiler_accounting
    job=PhaseBase.new; job.interpreted=true; job.send(:compile); job.send(:run)
    assert_equal [:run],job.events
  end
  def test_failed_accounting_reset_blocks_execution
    job=PhaseBase.new; job.send(:compile); job.reset_ok=false
    assert_raises(RuntimeError) { job.send(:run) }
    refute job.events.include?(:run)
  end
  def test_no_shared_environment_change
    original=ENV["CODELOBBY_ISOLATE_PHASE"]
    job=PhaseBase.new; job.send(:compile); job.send(:run)
    if original.nil?
      assert_nil ENV["CODELOBBY_ISOLATE_PHASE"]
    else
      assert_equal original,ENV["CODELOBBY_ISOLATE_PHASE"]
    end
  end
end
