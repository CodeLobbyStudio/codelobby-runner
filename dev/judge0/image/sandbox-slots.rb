# Process-wide sandbox ownership for the pinned worker. Never remove reservation files.
module CodeLobbySandboxSlots
  ROOT = "/var/lib/codelobby-worker-state/slots".freeze
  SIZE = 1000

  class Unavailable < StandardError
  end

  class Lease
    attr_reader :id

    def initialize(id, file)
      @id = id
      @file = file
    end

    # Call only after both workspace and Isolate cleanup have been confirmed.
    def release
      raise "Sandbox reservation already closed" if @file.closed?
      @file.truncate(0)
      @file.flush
      @file.fsync
      @file.close
    end

    # Keep the occupied marker even when the owning process no longer holds its lock.
    def quarantine
      @file.close unless @file.closed?
    end
  end

  class Pool
    def initialize(root = ROOT, size: SIZE)
      raise "Invalid sandbox pool size" unless size.is_a?(Integer) && (1..SIZE).cover?(size)
      @root = root
      @size = size
    end

    # Only restart recovery holding the matching job journal may claim a stale reservation.
    def recover(id, owner)
      raise "Invalid recovery sandbox" unless id.is_a?(Integer) && (0...@size).cover?(id)
      raise "Invalid sandbox owner" unless /\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z/.match?(owner.to_s)
      directory = File.lstat(@root)
      raise "Unsafe sandbox reservation directory" unless directory.directory? && directory.uid == Process.euid && (directory.mode & 0077).zero?
      file = File.open(File.join(@root, id.to_s), File::RDWR | File::NOFOLLOW)
      begin
        file.close_on_exec = true
        stat = file.stat
        raise "Unsafe sandbox reservation file" unless stat.file? && stat.nlink == 1 && stat.uid == Process.euid && (stat.mode & 0077).zero?
        raise Unavailable, "Sandbox still owned" unless file.flock(File::LOCK_EX | File::LOCK_NB)
        raise "Sandbox owner mismatch" if file.size > 128 || !["", "reserved #{owner}\n"].include?(file.read)
        file.rewind
        file.write("reserved #{owner}\n")
        file.flush
        file.fsync
        Lease.new(id, file)
      rescue Exception
        file.close
        raise
      end
    end

    def acquire(owner: nil)
      raise "Invalid sandbox owner" unless owner.nil? || /\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z/.match?(owner)
      directory = File.lstat(@root)
      unless directory.directory? && directory.uid == Process.euid && (directory.mode & 0077).zero?
        raise "Unsafe sandbox reservation directory"
      end
      @size.times do |id|
        file = File.open(File.join(@root, id.to_s), File::RDWR | File::CREAT | File::NOFOLLOW, 0600)
        leased = false
        begin
          file.close_on_exec = true
          stat = file.stat
          unless stat.file? && stat.nlink == 1 && stat.uid == Process.euid && (stat.mode & 0077).zero?
            raise "Unsafe sandbox reservation file"
          end
          next unless file.flock(File::LOCK_EX | File::LOCK_NB)
          # A marker left after a crash or failed cleanup is deliberately not recycled.
          next unless file.size.zero?
          file.write(owner ? "reserved #{owner}\n" : "reserved\n")
          file.flush
          file.fsync
          lease = Lease.new(id, file)
          leased = true
          return lease
        ensure
          file.close unless leased || file.closed?
        end
      end
      raise Unavailable, "No verified free sandbox slot"
    end
  end
end
