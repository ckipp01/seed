package seed.generation

import java.nio.file.{Files, Path, Paths}

import bloop.config.ConfigCodecs
import minitest.TestSuite
import org.apache.commons.io.FileUtils
import seed.{Log, cli}
import seed.Cli.{Command, PackageConfig}
import seed.cli.util.RTS
import seed.config.BuildConfig
import seed.generation.util.TestProcessHelper
import seed.generation.util.TestProcessHelper.ec
import seed.model.Config

import scala.concurrent.Future
import seed.generation.util.BuildUtil.tempPath

object BloopIntegrationSpec extends TestSuite[Unit] {
  override def setupSuite(): Unit    = TestProcessHelper.semaphore.acquire()
  override def tearDownSuite(): Unit = TestProcessHelper.semaphore.release()

  override def setup(): Unit             = ()
  override def tearDown(env: Unit): Unit = ()

  def readBloopJson(path: Path): bloop.config.Config.File = {
    val bytes = FileUtils.readFileToByteArray(path.toFile)
    ConfigCodecs.read(bytes).right.get
  }

  def compileAndRun(projectPath: Path) = {
    def compile =
      TestProcessHelper.runBloop(projectPath)("compile", "example").map { x =>
        assert(x.contains("Compiled example-jvm"))
        assert(x.contains("Compiled example-js"))
      }

    def run =
      TestProcessHelper
        .runBloop(projectPath)("run", "example-js", "example-jvm")
        .map { x =>
          assertEquals(x.split("\n").count(_ == "hello"), 2)
        }

    for { _ <- compile; _ <- run } yield ()
  }

  private val packageConfig = PackageConfig(
    tmpfs = false,
    silent = false,
    ivyPath = None,
    cachePath = None
  )

  test(
    "Generate project with duplicate transitive module dependencies"
  ) { _ =>
    val config =
      BuildConfig
        .load(Paths.get("test/duplicate-transitive-dep"), Log.urgent)
        .get
    import config._
    val buildPath = tempPath.resolve("duplicate-transitive-dep")
    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    val bloopBuildPath = buildPath.resolve("build").resolve("bloop")

    val bloopPath = buildPath.resolve(".bloop")
    val root      = readBloopJson(bloopPath.resolve("root.json"))
    val paths     = root.project.classpath.filter(_.startsWith(buildPath))
    assertEquals(
      paths,
      List(
        bloopBuildPath.resolve("a"),
        bloopBuildPath.resolve("b"),
        bloopBuildPath.resolve("shared")
      )
    )
  }

  testAsync("Generate and compile meta modules") { _ =>
    val projectPath = tempPath.resolve("meta-module")
    util.ProjectGeneration.generateBloopCrossProject(projectPath)
    compileAndRun(projectPath)
  }

  testAsync(
    "Build project with compiler plug-in defined on cross-platform module"
  ) { _ =>
    val config =
      BuildConfig.load(Paths.get("test/example-paradise"), Log.urgent).get
    import config._
    val buildPath = tempPath.resolve("example-paradise")
    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )
    compileAndRun(buildPath)
  }

  testAsync("Build project with compiler plug-in defined on platform modules") {
    _ =>
      val config = BuildConfig
        .load(Paths.get("test/example-paradise-platform"), Log.urgent)
        .get
      import config._
      val buildPath = tempPath.resolve("example-paradise-platform")
      Files.createDirectory(buildPath)
      cli.Generate.ui(
        Config(),
        projectPath,
        buildPath,
        resolvers,
        build,
        Command.Bloop(packageConfig),
        Log.urgent
      )
      compileAndRun(buildPath)
  }

  testAsync("Link JavaScript modules with custom target path") { _ =>
    val config =
      BuildConfig.load(Paths.get("test/submodule-output-path"), Log.urgent).get
    import config._
    val buildPath = tempPath.resolve("submodule-output-path")
    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )
    TestProcessHelper
      .runBloop(buildPath)("run", "app", "base", "base2")
      .map { x =>
        assertEquals(x.split("\n").count(_ == "hello"), 3)
        assert(
          Files
            .exists(buildPath.resolve("build").resolve("js").resolve("app.js"))
        )
        assert(
          Files
            .exists(buildPath.resolve("build").resolve("js").resolve("base.js"))
        )
        assert(Files.exists(buildPath.resolve("build").resolve("base2.js")))
      }
  }

  testAsync("Build project with overridden compiler plug-in version") { _ =>
    val projectPath = Paths.get("test/example-paradise-versions")
    val result      = BuildConfig.load(projectPath, Log.urgent).get
    import result._
    val buildPath = tempPath.resolve("example-paradise-versions")
    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    val bloopPath = buildPath.resolve(".bloop")

    val macrosJvm  = readBloopJson(bloopPath.resolve("macros-jvm.json"))
    val macrosJs   = readBloopJson(bloopPath.resolve("macros-js.json"))
    val exampleJvm = readBloopJson(bloopPath.resolve("example-jvm.json"))
    val exampleJs  = readBloopJson(bloopPath.resolve("example-js.json"))

    def getFileName(path: String): String = path.drop(path.lastIndexOf('/') + 1)

    assertEquals(
      macrosJvm.project.scala.get.options
        .filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.0.jar")
    )
    assertEquals(
      macrosJs.project.scala.get.options
        .filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.1.jar")
    )
    assertEquals(
      exampleJvm.project.scala.get.options
        .filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.0.jar")
    )
    assertEquals(
      exampleJs.project.scala.get.options
        .filter(_.contains("paradise"))
        .map(getFileName),
      List("paradise_2.11.12-2.1.1.jar")
    )

    def checkResolutionArtefacts(configFile: bloop.config.Config.File): Unit = {
      val resolution = configFile.project.resolution.get.modules
      configFile.project.classpath
        .filter(_.toString.endsWith(".jar"))
        .foreach { cp =>
          assert(
            resolution.exists(_.artifacts.exists(_.path == cp)),
            s"Missing artefact: $cp"
          )

          // By default, only fetch class artefacts unless user enabled
          // `optionalArtefacts` in Seed configuration
          assert(
            !resolution.exists(_.artifacts.exists(_.classifier.isDefined)),
            s"Classifier should be empty: $cp"
          )
        }
    }

    checkResolutionArtefacts(macrosJvm)
    checkResolutionArtefacts(macrosJs)
    checkResolutionArtefacts(exampleJvm)
    checkResolutionArtefacts(exampleJs)

    compileAndRun(buildPath)
  }

  testAsync("Build modules with different Scala versions") { _ =>
    val config = BuildConfig
      .load(Paths.get("test/multiple-scala-versions"), Log.urgent)
      .get
    import config._
    val buildPath = tempPath.resolve("multiple-scala-versions-bloop")
    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )
    TestProcessHelper
      .runBloop(buildPath)("run", "module211", "module212")
      .map { x =>
        val lines = x.split("\n").toList
        assert(lines.contains("2.11.11"))
        assert(lines.contains("2.12.8"))
      }
  }

  def buildCustomTarget(
    name: String,
    expectFailure: Boolean = false
  ): Future[Unit] = {
    val path = Paths.get(s"test/$name")

    val config = BuildConfig.load(path, Log.urgent).get
    import config._
    val buildPath = tempPath.resolve(name)
    Files.createDirectory(buildPath)
    val generatedFile = projectPath.resolve("demo").resolve("Generated.scala")
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    val result = seed.cli.Build.build(
      path,
      Some(buildPath),
      List("demo"),
      watch = false,
      tmpfs = false,
      if (expectFailure) Log.silent else Log.urgent,
      _ => _ => ()
    )

    val uio = result.right.get

    if (expectFailure) RTS.unsafeRunToFuture(uio).failed.map(_ => ())
    else {
      RTS.unsafeRunSync(uio)
      assert(Files.exists(generatedFile))

      TestProcessHelper
        .runBloop(buildPath)("run", "demo")
        .map { x =>
          assertEquals(x.split("\n").count(_ == "42"), 1)
          Files.delete(generatedFile)
        }
    }
  }

  testAsync("Build project with custom class target") { _ =>
    buildCustomTarget("custom-class-target")
  }

  testAsync("Build project with custom command target") { _ =>
    buildCustomTarget("custom-command-target").map { _ =>
      val path = tempPath
        .resolve("custom-command-target")
        .resolve(".bloop")
        .resolve("demo.json")
      val result = readBloopJson(path)

      // Do not include the `utils` classpath since the module is only a custom
      // build target and does not have a JVM target.
      assert(!result.project.classpath.exists(_.toString.contains("/utils")))
      assert(result.project.classpath.forall(Files.exists(_)))

      // Should not include "utils" dependency since it does not have any
      // Scala sources and no Bloop module.
      assertEquals(result.project.dependencies, List())
    }
  }

  testAsync("Build project with failing custom command target") { _ =>
    buildCustomTarget("custom-command-target-fail", expectFailure = true)
  }

  testAsync("Generate non-JVM project") { _ =>
    val config = BuildConfig
      .load(Paths.get("test/shared-module"), Log.urgent)
      .get
    import config._
    val buildPath = tempPath.resolve("shared-module-bloop")
    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    TestProcessHelper
      .runBloop(buildPath)("run", "example-js", "example-native")
      .map { output =>
        val lines = output.split("\n")
        assert(lines.contains("js"))
        assert(lines.contains("native"))
      }
  }
}
