require "minitest/autorun"
require "tmpdir"
require "fileutils"
require File.join(File.dirname(ARGV.fetch(0)), "sandbox-slots")
require ARGV.fetch(0)

class WorkerRecoveryTest < Minitest::Test
  TOKEN = "00000000-0000-0000-0000-000000000001".freeze
  OTHER = "00000000-0000-0000-0000-000000000002".freeze

  def setup
    @root = Dir.mktmpdir("codelobby-recovery-test-")
    @jobs = File.join(@root, "jobs")
    @slots = File.join(@root, "slots")
    Dir.mkdir(@jobs, 0700)
    Dir.mkdir(@slots, 0700)
    @store = CodeLobbyWorkerRecovery::Store.new(@jobs)
    @pool = CodeLobbySandboxSlots::Pool.new(@slots, size: 2)
    @leases = []
    @journals = []
    @events = []
  end

  def teardown
    @journals.each(&:close)
    @leases.each(&:quarantine)
    FileUtils.remove_entry(@root)
  end

  def interrupted(slot: true)
    journal = @store.start(7, TOKEN)
    @journals << journal
    if slot
      lease = @pool.acquire(owner: TOKEN)
      @leases << lease
      journal.assign(lease.id)
      lease.quarantine
    end
    journal.close
    journal
  end

  def recover(cleanup: ->(id) { @events << [:cleanup, id] }, finish: ->(job) { @events << [:finish, job] }, find: ->(record) { @events << [:find, record["id"]]; record["id"] })
    CodeLobbyWorkerRecovery.recover(store: @store, pool: @pool, find: find, cleanup: cleanup, finish: finish)
  end

  def test_persists_assignment_and_finishes_only_after_verified_cleanup
    interrupted
    recover
    assert_equal [[:find, 7], [:cleanup, 0], [:finish, 7]], @events
    assert_empty Dir.glob(File.join(@jobs, "*.json"))
    lease = @pool.acquire
    @leases << lease
    assert_equal 0, lease.id
    recover
    assert_equal 3, @events.size
  end

  def test_before_sandbox_assignment_requires_no_resource_cleanup
    interrupted(slot: false)
    recover
    assert_equal [[:find, 7], [:finish, 7]], @events
    assert_empty Dir.glob(File.join(@slots, "*"))
  end

  def test_cleanup_failure_retains_journal_and_reservation_without_finishing
    interrupted
    assert_raises(RuntimeError) { recover(cleanup: ->(_) { raise "Cleanup unconfirmed" }) }
    assert_equal [[:find, 7]], @events
    assert File.exist?(File.join(@jobs, "7.json"))
    assert_equal "reserved #{TOKEN}\n", File.read(File.join(@slots, "0"))
    recover
    assert_equal [:finish, 7], @events.last
  end

  def test_database_failure_does_not_release_or_forget_job
    interrupted
    assert_raises(RuntimeError) { recover(finish: ->(_) { raise "Database unavailable" }) }
    assert File.exist?(File.join(@jobs, "7.json"))
    assert_equal "reserved #{TOKEN}\n", File.read(File.join(@slots, "0"))
    recover
    assert_equal [:finish, 7], @events.last
  end

  def test_live_job_prevents_startup_recovery
    @journals << @store.start(7, TOKEN)
    assert_raises(RuntimeError) { recover }
    assert_empty @events
  end

  def test_live_sandbox_prevents_cleanup_even_if_journal_lock_is_released
    journal = @store.start(7, TOKEN)
    @journals << journal
    lease = @pool.acquire(owner: TOKEN)
    @leases << lease
    journal.assign(lease.id)
    journal.close
    assert_raises(CodeLobbySandboxSlots::Unavailable) { recover }
    assert_equal [[:find, 7]], @events
  end

  def test_mismatched_slot_owner_is_never_cleaned_or_finished
    interrupted
    File.write(File.join(@slots, "0"), "reserved #{OTHER}\n")
    assert_raises(RuntimeError) { recover }
    assert_equal [[:find, 7]], @events
    assert_equal "reserved #{OTHER}\n", File.read(File.join(@slots, "0"))
  end

  def test_mismatched_database_identity_is_checked_before_cleanup
    interrupted
    assert_raises(RuntimeError) { recover(find: ->(_) { raise "Identity mismatch" }) }
    assert_empty @events
    assert File.exist?(File.join(@jobs, "7.json"))
  end

  def test_crash_after_slot_release_can_be_reconciled_without_reusing_an_owned_slot
    journal = @store.start(7, TOKEN)
    @journals << journal
    lease = @pool.acquire(owner: TOKEN)
    @leases << lease
    journal.assign(lease.id)
    lease.release
    journal.close
    recover
    assert_equal [:finish, 7], @events.last
  end

  def test_partial_or_unknown_journals_block_recovery
    interrupted(slot: false)
    File.write(File.join(@jobs, "7.json"), '{"id":7}')
    assert_raises(RuntimeError) { recover }
    assert_empty @events
  end

  def test_journal_symlink_is_rejected_without_reading_its_target
    interrupted(slot: false)
    original = File.join(@root, "original")
    File.rename(File.join(@jobs, "7.json"), original)
    File.symlink(original, File.join(@jobs, "7.json"))
    assert_raises(Errno::ELOOP) { recover }
    assert File.exist?(original)
    assert_empty @events
  end

  def test_prevents_duplicate_live_job_and_invalid_assignment
    journal = @store.start(7, TOKEN)
    @journals << journal
    assert_raises(RuntimeError) { @store.start(7, TOKEN) }
    assert_raises(RuntimeError) { journal.assign(1000) }
    journal.assign(1)
    assert_raises(RuntimeError) { journal.assign(0) }
  end

  def test_journal_survives_owner_process_death
    pid = fork do
      store = CodeLobbyWorkerRecovery::Store.new(@jobs)
      journal = store.start(7, TOKEN)
      lease = @pool.acquire(owner: TOKEN)
      journal.assign(lease.id)
      exit!(0)
    end
    _, status = Process.wait2(pid)
    assert status.success?
    recover
    assert_equal [[:find, 7], [:cleanup, 0], [:finish, 7]], @events
  end
end
