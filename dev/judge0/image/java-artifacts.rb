require "base64"
require "json"

# Linux-only, bounded snapshot. Call only after verifying the compiler cgroup is empty.
module CodeLobbyJavaArtifacts
  MAX_FILES = 256
  MAX_ENTRIES = 512
  MAX_FILE_BYTES = 262_144
  MAX_TOTAL_BYTES = 1_048_576
  MAX_OUTPUT_BYTES = 1_500_000
  FLAGS = File::RDONLY | File::NOFOLLOW | File::NONBLOCK
  SEGMENT = /\A[A-Za-z0-9_$-]+\z/
  CLASS_FILE = /\A[A-Za-z0-9_$-]+\.class\z/
  class Rejected < StandardError; end
  class TooLarge < StandardError; end

  def self.open_child(parent, name)
    path = "/proc/self/fd/#{parent.fileno}/#{name}"
    before = File.lstat(path)
    raise Rejected unless before.file? || before.directory?
    File.open(path, FLAGS) do |entry|
      after = entry.stat
      raise Rejected unless before.ino == after.ino && before.dev == after.dev && after.dev == parent.stat.dev
      yield entry
    end
  end

  def self.capture(box)
    raise Rejected unless box.stat.directory?
    files = []
    state = {entries: 0, bytes: 0}
    open_child(box, ".codelobby") do |internal|
      raise Rejected unless internal.stat.directory?
      open_child(internal, "classes") do |classes|
        raise Rejected unless classes.stat.directory?
        walk(classes, "", files, state)
      end
    end
    raise Rejected if files.empty?
    payload = JSON.generate(version: 1, files: files.sort_by { |file| file[:path] }).b
    raise TooLarge if payload.bytesize > MAX_OUTPUT_BYTES
    payload.freeze
  rescue Errno::ENOENT, Errno::ENOTDIR, Errno::ELOOP, Errno::EACCES
    raise Rejected
  end

  def self.walk(directory, prefix, files, state)
    Dir.foreach("/proc/self/fd/#{directory.fileno}") do |name|
      next if name == "." || name == ".."
      raise Rejected unless name.ascii_only?
      state[:entries] += 1
      raise TooLarge if state[:entries] > MAX_ENTRIES
      path = prefix + name
      raise Rejected if path.bytesize > 240 || path.count("/") > 31
      open_child(directory, name) do |entry|
        stat = entry.stat
        if stat.directory?
          raise Rejected unless SEGMENT.match?(name)
          walk(entry, path + "/", files, state)
        else
          raise Rejected unless CLASS_FILE.match?(name) && stat.file? && stat.nlink == 1
          raise TooLarge if stat.size > MAX_FILE_BYTES || state[:bytes] + stat.size > MAX_TOTAL_BYTES
          bytes = entry.read(MAX_FILE_BYTES + 1) || "".b
          raise TooLarge if bytes.bytesize > MAX_FILE_BYTES || state[:bytes] + bytes.bytesize > MAX_TOTAL_BYTES
          raise Rejected unless bytes.bytesize == stat.size && bytes.start_with?("\xCA\xFE\xBA\xBE".b)
          state[:bytes] += bytes.bytesize
          files << {path: path, contentBase64: Base64.strict_encode64(bytes)}
          raise TooLarge if files.size > MAX_FILES
        end
      end
    end
  rescue Errno::ENOENT, Errno::ENOTDIR, Errno::ELOOP, Errno::EACCES
    raise Rejected
  end
end
