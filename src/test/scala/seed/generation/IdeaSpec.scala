package seed.generation

import minitest.SimpleTestSuite
import java.nio.file.Paths

import org.apache.commons.io.FileUtils
import seed.Cli.{Command, PackageConfig}
import seed.{Log, cli}
import seed.artefact.ArtefactResolution
import seed.config.BuildConfig
import seed.model.Build.{Module, Project}
import seed.model.Platform.JVM
import seed.model.{Build, Config}

object IdeaSpec extends SimpleTestSuite {
  test("Normalise paths") {
    assertEquals(
      Idea.normalisePath(Idea.ModuleDir, Paths.get("/tmp"))(Paths.get("/tmp")),
      "$MODULE_DIR$/")

    assertEquals(
      Idea.normalisePath(Idea.ModuleDir, Paths.get("/tmp/.idea/modules"))(
        Paths.get("/tmp/src")
      ), "$MODULE_DIR$/../../src")

    assertEquals(
      Idea.normalisePath(Idea.ModuleDir, Paths.get(".idea/modules"))(Paths.get("/tmp/build")),
      "/tmp/build")
  }

  test("Generate modules") {
    val build =
      Build(
        project = Project("2.12.8"),
        module = Map(
          "a" -> Module(
            targets = List(JVM),
            jvm = Some(Module(
              root = Some(Paths.get("a")),
              sources = List(Paths.get("a/src"))))),
          "b" -> Module(
            targets = List(JVM),
            jvm = Some(Module(
              root = Some(Paths.get("b")),
              sources = List(Paths.get("b/src")))))))

    val projectPath = Paths.get(".")
    val outputPath = Paths.get("/tmp")
    val packageConfig = PackageConfig(false, false, None, None)
    val compilerDeps0 = ArtefactResolution.allCompilerDeps(build)
    val (_, platformResolution, compilerResolution) =
      ArtefactResolution.resolution(seed.model.Config(), build, packageConfig,
        optionalArtefacts = false, Set(), compilerDeps0)

    Idea.build(projectPath, outputPath, build, platformResolution,
      compilerResolution, false)
  }

  test("Generate project with custom compiler options") {
    val (projectPath, build) = BuildConfig.load(
      Paths.get("test/compiler-options"), Log).get
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Build.ui(Config(), projectPath, build, Command.Idea(packageConfig))

    val ideaPath = projectPath.resolve(".idea")

    val scalaCompiler =
      pine.XmlParser.fromString(FileUtils.readFileToString(
        ideaPath.resolve("scala_compiler.xml").toFile, "UTF-8"))

    val profileNodes = scalaCompiler.byTagAll["profile"]
    assertEquals(profileNodes.length, 1)
    assertEquals(profileNodes.head.attr("modules"),
      Some("demo,demo-jvm,demo-js"))
    assertEquals(
      profileNodes.head.byTag["parameter"].attr("value"),
      Some("-Yliteral-types"))
  }

  test("Generate project with different Scala versions") {
    val (projectPath, build) = BuildConfig.load(
      Paths.get("test/multiple-scala-versions"), Log).get
    val packageConfig = PackageConfig(tmpfs = false, silent = false,
      ivyPath = None, cachePath = None)
    cli.Build.ui(Config(), projectPath, build, Command.Idea(packageConfig))

    val ideaPath = projectPath.resolve(".idea")

    val scalaCompiler =
      pine.XmlParser.fromString(FileUtils.readFileToString(
        ideaPath.resolve("scala_compiler.xml").toFile, "UTF-8"))

    val profileNodes = scalaCompiler.byTagAll["profile"]
    assertEquals(profileNodes.length, 1)
    assertEquals(profileNodes.head.attr("modules"),
      Some("module212,module211"))

    val module211 =
      pine.XmlParser.fromString(FileUtils.readFileToString(
        ideaPath.resolve("modules").resolve("module211.iml").toFile,
        "UTF-8"))
    val libraries211 = module211.byTagAll["orderEntry"]
      .filter(_.attr("type").contains("library"))
      .flatMap(_.attr("name"))
    assertEquals(libraries211,
      List("org.scala-lang-2.11.11", "sourcecode_2.11-0.1.5.jar"))

    val module212 =
      pine.XmlParser.fromString(FileUtils.readFileToString(
        ideaPath.resolve("modules").resolve("module212.iml").toFile,
        "UTF-8"))
    val libraries212 = module212.byTagAll["orderEntry"]
      .filter(_.attr("type").contains("library"))
      .flatMap(_.attr("name"))
    assertEquals(libraries212,
      List("org.scala-lang-2.12.8", "sourcecode_2.12-0.1.5.jar"))
  }
}
