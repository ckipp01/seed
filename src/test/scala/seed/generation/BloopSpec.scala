package seed.generation

import java.nio.file.{Path, Paths}

import bloop.config.ConfigEncoderDecoders
import minitest.SimpleTestSuite
import org.apache.commons.io.FileUtils

object BloopSpec extends SimpleTestSuite {
  def parseBloopFile(path: Path): bloop.config.Config.File = {
    val json = FileUtils.readFileToString(path.toFile, "UTF-8")
    io.circe.parser.decode(json)(ConfigEncoderDecoders.allDecoder).right.get
  }

  test("Inherit javaDeps in child modules") {
    val projectPath = Paths.get("test/inherit-javadeps")
    val bloopPath = projectPath.resolve(".bloop")
    util.ProjectGeneration.generateJavaDepBloopProject(projectPath)

    val base = parseBloopFile(bloopPath.resolve("base.json"))
    assert(base.project.classpath.exists(_.toString.contains("/org/postgresql/postgresql/")))

    val example = parseBloopFile(bloopPath.resolve("example.json"))
    assert(example.project.classpath.exists(_.toString.contains("/org/postgresql/postgresql/")))

    val exampleTest = parseBloopFile(bloopPath.resolve("example-test.json"))
    assert(exampleTest.project.classpath.exists(_.toString.contains("/org/postgresql/postgresql/")))
  }
}