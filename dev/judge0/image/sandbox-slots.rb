# Process-wide sandbox ownership for the pinned worker. Never remove reservation files.
module CodeLobbySandboxSlots
  ROOT = "/run/codelobby-sandbox-slots".freeze
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

    def acquire
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
          file.write("reserved\n")
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
