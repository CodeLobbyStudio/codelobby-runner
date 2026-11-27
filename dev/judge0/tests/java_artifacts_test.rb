require "minitest/autorun"
require "tmpdir"
require "fileutils"
require ARGV.fetch(0)

class JavaArtifactsTest < Minitest::Test
  MAGIC = "\xCA\xFE\xBA\xBE".b

  def setup
    @root = Dir.mktmpdir
    @classes = File.join(@root, ".codelobby", "classes")
    FileUtils.mkdir_p(@classes)
  end

  def teardown
    FileUtils.remove_entry(@root)
  end

  def write(name, content = MAGIC + "original".b)
    path = File.join(@classes, name)
    FileUtils.mkdir_p(File.dirname(path))
    File.binwrite(path, content)
  end

  def capture
    File.open(@root, CodeLobbyJavaArtifacts::FLAGS) { |box| CodeLobbyJavaArtifacts.capture(box) }
  end

  def test_round_trips_binary_classes_in_deterministic_order
    write("z/Inner$1.class", MAGIC + "\x00\xFF".b)
    write("a/package-info.class")
    snapshot = capture
    parsed = JSON.parse(snapshot)
    assert_equal 1, parsed["version"]
    assert_equal ["a/package-info.class", "z/Inner$1.class"], parsed["files"].map { |file| file["path"] }
    assert_equal MAGIC + "\x00\xFF".b, Base64.strict_decode64(parsed["files"].last["contentBase64"])
    assert_equal snapshot, capture
    assert snapshot.frozen?
  end

  def test_runtime_mutation_or_deletion_does_not_change_the_snapshot
    write("Solution.class")
    snapshot = capture
    write("Solution.class", MAGIC + "participant-mutation")
    refute_equal snapshot, capture
    File.unlink(File.join(@classes, "Solution.class"))
    assert_equal MAGIC + "original", Base64.strict_decode64(JSON.parse(snapshot)["files"].first["contentBase64"])
  end

  def test_rejects_symlinks_at_every_untrusted_level
    write("Solution.class")
    File.symlink("Solution.class", File.join(@classes, "Linked.class"))
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
    File.unlink(File.join(@classes, "Linked.class"))
    File.symlink("..", File.join(@classes, "linked"))
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
    File.unlink(File.join(@classes, "linked"))
    File.rename(@classes, @classes + "-real")
    File.symlink(@classes + "-real", @classes)
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
  end

  def test_rejects_hardlinked_files
    write("Solution.class")
    File.link(File.join(@classes, "Solution.class"), File.join(@classes, "Linked.class"))
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
  end

  def test_rejects_fifo_without_blocking
    system("mkfifo", File.join(@classes, "Pipe.class")) or raise "Could not prepare test FIFO"
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
  end

  def test_rejects_source_reports_and_other_files
    ["Solution.java", "report.ndjson", "..class", "bad name.class", "ż.class"].each do |name|
      write(name)
      assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
      File.unlink(File.join(@classes, name))
    end
  end

  def test_rejects_invalid_class_magic_and_empty_snapshots
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
    write("Bad.class", "not-bytecode")
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
  end

  def test_enforces_per_file_and_total_byte_boundaries
    full = MAGIC + "a" * (CodeLobbyJavaArtifacts::MAX_FILE_BYTES - MAGIC.bytesize)
    4.times { |i| write("C#{i}.class", full) }
    assert_equal 4, JSON.parse(capture)["files"].size
    write("Extra.class", MAGIC)
    assert_raises(CodeLobbyJavaArtifacts::TooLarge) { capture }
    File.unlink(File.join(@classes, "Extra.class"))
    write("C0.class", full + "a")
    assert_raises(CodeLobbyJavaArtifacts::TooLarge) { capture }
  end

  def test_enforces_file_count_boundary
    256.times { |i| write("C#{i}.class", MAGIC) }
    assert_equal 256, JSON.parse(capture)["files"].size
    write("Extra.class", MAGIC)
    assert_raises(CodeLobbyJavaArtifacts::TooLarge) { capture }
  end

  def test_bounds_empty_directory_traversal
    write("Solution.class")
    513.times { |i| FileUtils.mkdir_p(File.join(@classes, "d#{i}")) }
    assert_raises(CodeLobbyJavaArtifacts::TooLarge) { capture }
  end

  def test_rejects_non_utf8_names_without_exposing_them
    write("\xFF.class".b)
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
  end

  def test_rejects_excessive_path_length
    write("a" * 235 + ".class")
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
  end

  def test_rejects_missing_class_directory
    FileUtils.remove_entry(@classes)
    assert_raises(CodeLobbyJavaArtifacts::Rejected) { capture }
  end
end
