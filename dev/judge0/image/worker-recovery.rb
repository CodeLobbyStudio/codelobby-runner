# Restart-only recovery. No age threshold is treated as proof of process termination.
require "json"
require "securerandom"
require "/usr/local/lib/codelobby/sandbox-slots" unless defined?(CodeLobbySandboxSlots)

module CodeLobbyWorkerRecovery
  ROOT = "/run/codelobby-worker-jobs".freeze
  TOKEN = /\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z/
  MESSAGE = "Worker interrupted. Sandbox termination and cleanup verified.".freeze

  class Journal
    attr_reader :record

    def initialize(store, id, lock, record)
      @store, @id, @lock, @record = store, id, lock, record
    end

    def assign(slot)
      raise "Invalid recovery sandbox" unless slot.is_a?(Integer) && (0..999).cover?(slot)
      raise "Sandbox already assigned" unless @record["slot"].nil?
      @record = @record.merge("slot" => slot)
      @store.write(@id, @record)
    end

    def complete
      @store.remove(@id)
    end

    def close
      @lock.close unless @lock.closed?
    end
  end

  class Store
    def initialize(root = ROOT)
      @root = root
      stat = File.lstat(root)
      raise "Unsafe worker journal directory" unless stat.directory? && stat.uid == Process.euid && (stat.mode & 0077).zero?
    end

    def start(id, token)
      valid_id!(id)
      raise "Invalid recovery token" unless TOKEN.match?(token.to_s)
      lock = lock(id)
      raise "Submission already owned" unless lock
      begin
        raise "Submission recovery required" if File.exist?(path(id)) || File.symlink?(path(id))
        record = {"version" => 1, "id" => id, "token" => token, "slot" => nil}
        write(id, record)
        Journal.new(self, id, lock, record)
      rescue Exception
        lock.close
        raise
      end
    end

    def pending
      Dir.glob(File.join(@root, "*.json")).sort.each do |entry|
        basename = File.basename(entry, ".json")
        raise "Invalid worker journal name" unless /\A[1-9][0-9]{0,18}\z/.match?(basename)
        id = Integer(basename, 10)
        owned = lock(id)
        # Startup must not race another live worker or silently ignore its journals.
        raise "Worker journal is still owned" unless owned
        begin
          record = read(id)
          yield Journal.new(self, id, owned, record)
        ensure
          owned.close unless owned.closed?
        end
      end
    end

    def write(id, record)
      temporary = File.join(@root, ".#{id}-#{SecureRandom.hex(16)}")
      begin
        File.open(temporary, File::WRONLY | File::CREAT | File::EXCL | File::NOFOLLOW, 0600) do |file|
          file.write(JSON.generate(record))
          file.flush
          file.fsync
        end
        File.rename(temporary, path(id))
        sync_directory
      ensure
        File.unlink(temporary) if File.exist?(temporary)
      end
    end

    def remove(id)
      File.unlink(path(id))
      sync_directory
    end

    private

    def valid_id!(id)
      raise "Invalid recovery submission ID" unless id.is_a?(Integer) && (1..9223372036854775807).cover?(id)
    end

    def path(id)
      File.join(@root, "#{id}.json")
    end

    def lock(id)
      valid_id!(id)
      file = secure_open(File.join(@root, "#{id}.lock"), File::RDWR | File::CREAT)
      return file if file.flock(File::LOCK_EX | File::LOCK_NB)
      file.close
      nil
    end

    def secure_open(name, flags)
      file = File.open(name, flags | File::NOFOLLOW, 0600)
      begin
        file.close_on_exec = true
        stat = file.stat
        raise "Unsafe worker journal file" unless stat.file? && stat.nlink == 1 && stat.uid == Process.euid && (stat.mode & 0077).zero?
        file
      rescue Exception
        file.close
        raise
      end
    end

    def read(id)
      file = secure_open(path(id), File::RDONLY)
      begin
        raise "Worker journal too large" if file.size > 4096
        record = JSON.parse(file.read)
        unless record.is_a?(Hash) && record.keys.sort == %w[id slot token version] && record["version"] == 1 &&
               record["id"] == id && TOKEN.match?(record["token"].to_s) &&
               (record["slot"].nil? || (record["slot"].is_a?(Integer) && (0..999).cover?(record["slot"])))
          raise "Invalid worker journal"
        end
        record
      ensure
        file.close
      end
    end

    def sync_directory
      File.open(@root, File::RDONLY) { |directory| directory.fsync }
    end
  end

  def self.recover(store:, pool:, find:, cleanup:, finish:)
    store.pending do |journal|
      record = journal.record
      # Check the DB identity before touching a sandbox from this journal.
      submission = find.call(record)
      lease = nil
      begin
        unless record["slot"].nil?
          lease = pool.recover(record["slot"], record["token"])
          cleanup.call(lease.id)
        end
        finish.call(submission)
        lease.release if lease
        journal.complete
      ensure
        lease.quarantine if lease
      end
    end
  end

  module Job
    def perform(submission_id)
      token = Submission.find(submission_id).token
      @codelobby_journal = CodeLobbyWorkerRecovery::Store.new.start(submission_id, token)
      @codelobby_owner_token = token
      completed = false
      begin
        super
        completed = true
      ensure
        # Keep a durable journal whenever cleanup was not confirmed or perform escaped.
        begin
          @codelobby_journal.complete if completed && @codelobby_sandbox_lease.nil?
        ensure
          @codelobby_journal.close
          @codelobby_owner_token = nil
        end
      end
    end
  end
end
