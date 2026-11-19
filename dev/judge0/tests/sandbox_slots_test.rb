require "minitest/autorun"
require "tmpdir"
require "fileutils"
require ARGV.fetch(0)

class SandboxSlotsTest < Minitest::Test
  def setup
    @root = Dir.mktmpdir("codelobby-slot-test-")
    @leases = []
  end

  def teardown
    @leases.each(&:quarantine)
    FileUtils.remove_entry(@root)
  end

  def pool(size = 2)
    CodeLobbySandboxSlots::Pool.new(@root, size: size)
  end

  def acquire(source = pool)
    lease = source.acquire
    @leases << lease
    lease
  end

  def test_independent_callers_reserve_distinct_ids_and_report_exhaustion
    assert_equal 0, acquire.id
    assert_equal 1, acquire.id
    assert_raises(CodeLobbySandboxSlots::Unavailable) { pool.acquire }
  end

  def test_confirmed_release_allows_id_reuse
    first = acquire(pool(1))
    first.release
    assert_equal 0, acquire(pool(1)).id
    assert_raises(RuntimeError) { first.release }
  end

  def test_quarantine_preserves_occupied_marker_after_lock_is_closed
    first = acquire
    first.quarantine
    assert_equal "reserved\n", File.read(File.join(@root, "0"))
    assert_equal 1, acquire.id
  end

  def test_an_empty_slot_file_held_by_another_owner_is_not_reused
    File.open(File.join(@root, "0"), File::RDWR | File::CREAT, 0600) do |file|
      assert file.flock(File::LOCK_EX | File::LOCK_NB)
      assert_equal 1, acquire.id
    end
    assert_equal 0, acquire.id
  end

  def test_process_death_does_not_recycle_an_unconfirmed_sandbox
    pid = fork do
      lease = pool(1).acquire
      exit!(lease.id == 0 ? 0 : 1)
    end
    _, status = Process.wait2(pid)
    assert status.success?
    assert_raises(CodeLobbySandboxSlots::Unavailable) { pool(1).acquire }
    assert_equal "reserved\n", File.read(File.join(@root, "0"))
  end

  def test_multiple_processes_never_own_the_same_id
    readers = []
    gates = []
    children = []
    begin
      4.times do
        read_id, write_id = IO.pipe
        read_gate, write_gate = IO.pipe
        pid = fork do
          read_id.close
          write_gate.close
          lease = pool(4).acquire
          write_id.puts(lease.id)
          write_id.close
          read_gate.read(1)
          lease.release
          exit! 0
        end
        write_id.close
        read_gate.close
        readers << read_id
        gates << write_gate
        children << pid
      end
      ids = readers.map do |reader|
        refute_nil IO.select([reader], nil, nil, 5), "Reservation process did not respond"
        Integer(reader.gets)
      end
      assert_equal [0, 1, 2, 3], ids.sort
      assert_raises(CodeLobbySandboxSlots::Unavailable) { pool(4).acquire }
    ensure
      gates.each do |gate|
        gate.write("x") rescue Errno::EPIPE
        gate.close
      end
      readers.each(&:close)
      children.each do |pid|
        _, status = Process.wait2(pid)
        assert status.success?
      end
    end
    assert_equal 0, acquire(pool(4)).id
  end

  def test_rejects_unsafe_directory_permissions
    File.chmod(0777, @root)
    assert_raises(RuntimeError) { pool.acquire }
  end

  def test_rejects_symlinked_pool_and_slot_files
    outside = File.join(@root, "outside")
    File.write(outside, "must remain unchanged")
    File.symlink(outside, File.join(@root, "0"))
    assert_raises(Errno::ELOOP) { pool.acquire }
    assert_equal "must remain unchanged", File.read(outside)
    link = @root + "-link"
    begin
      File.symlink(@root, link)
      assert_raises(RuntimeError) { CodeLobbySandboxSlots::Pool.new(link).acquire }
    ensure
      File.unlink(link) if File.symlink?(link)
    end
  end

  def test_rejects_hardlinked_and_group_writable_slot_files
    source = File.join(@root, "source")
    File.write(source, "")
    File.chmod(0600, source)
    File.link(source, File.join(@root, "0"))
    assert_raises(RuntimeError) { pool.acquire }
    File.unlink(File.join(@root, "0"))
    File.write(File.join(@root, "0"), "")
    File.chmod(0660, File.join(@root, "0"))
    assert_raises(RuntimeError) { pool.acquire }
  end

  def test_rejects_invalid_pool_sizes
    [0, -1, 1001, "2"].each do |size|
      assert_raises(RuntimeError) { CodeLobbySandboxSlots::Pool.new(@root, size: size) }
    end
  end
end
