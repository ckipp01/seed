package seed.generation.util

import pine._

/** XML writers for IDEA files */
object IdeaFile {
  case class Output(classPath: String, testClassPath: String)
  case class Module(projectId: String,
                    rootPath: String,
                    sourcePaths: List[String],
                    testPaths: List[String],
                    libraries: List[String],
                    testLibraries: List[String],
                    moduleDeps: List[String],
                    output: Option[Output])

  case class Library(name: String,
                     isScalaCompiler: Boolean,
                     compilerClasses: List[String],
                     classes: List[String],
                     javaDoc: List[String],
                     sources: List[String])

  def createModule(module: Module): String = {
    val output = module.output.toList.flatMap { output =>
      List(
        xml"""<output url="file://${output.classPath}" />""",
        xml"""<output-test url="file://${output.testClassPath}" />""")
    }

    val sourceFolders = module.sourcePaths.map { path =>
      xml"""<sourceFolder url="file://$path" isTestSource="false" />"""
    }

    val testFolders = module.testPaths.map { path =>
      xml"""<sourceFolder url="file://$path" isTestSource="true" />"""
    }

    val libraries = module.libraries.map { library =>
      xml"""<orderEntry type="library" name=$library level="project" />"""
    }

    val testLibraries = module.testLibraries.map { library =>
      xml"""<orderEntry type="library" scope="TEST" name=$library level="project" />"""
    }

    val moduleDeps = module.moduleDeps.map { dep =>
      xml"""<orderEntry type="module" module-name=$dep />"""
    }

    xml"""
    <?xml version="1.0" encoding="UTF-8"?>
    <module type="JAVA_MODULE" version="4">
      <component name="NewModuleRootManager" LANGUAGE_LEVEL="JDK_1_8">
        $output
        <exclude-output />
        <content url="file://${module.rootPath}">
          $sourceFolders
          $testFolders
        </content>
        <orderEntry type="inheritedJdk" />
        $libraries
        $testLibraries
        $moduleDeps
      </component>
    </module>
    """.toXml
  }

  def createProject(modulePaths: List[String]): String = {
    val modules = modulePaths.map { path =>
      xml"""<module fileurl="file://$path" filepath=$path />"""
    }

    xml"""
    <?xml version="1.0" encoding="UTF-8"?>
    <project version="4">
      <component name="ProjectModuleManager">
        <modules>$modules</modules>
      </component>
    </project>
    """.toXml
  }

  def createLibrary(library: Library): String = {
    val tpe = if (library.isScalaCompiler) Some("Scala") else None
    val compilerPaths = library.compilerClasses
      .map(path => xml"""<root url="file://$path" />""")
    val properties = if (compilerPaths.isEmpty) List() else List(xml"""
      <properties><compiler-classpath>$compilerPaths</compiler-classpath></properties>
    """)
    val classes = library.classes.map(path => xml"""<root url="jar://$path!/" />""")
    val javaDoc = library.javaDoc.map(path => xml"""<root url="jar://$path!/" />""")
    val sources = library.sources.map(path => xml"""<root url="jar://$path!/" />""")

    xml"""
    <component name="libraryTable">
      <library name=${library.name} type=$tpe>
        $properties
        <CLASSES>$classes</CLASSES>
        <JAVADOC>$javaDoc</JAVADOC>
        <SOURCES>$sources</SOURCES>
      </library>
    </component>
    """.toXml
  }

  def createScalaCompiler(scalaOptions: List[String], modules: List[String]): String = {
    val parameters = scalaOptions.map(option =>
      xml"<parameter value=$option />")

    val modulesAttr = modules.mkString(",")

    xml"""
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="ScalaCompilerConfiguration">
          <option name="incrementalityType" value="IDEA" />
          <profile name="Default Profile" modules=$modulesAttr>
            <parameters>$parameters</parameters>
          </profile>
        </component>
      </project>
    """.toXml
  }

  def createJdk(jdkVersion: String): String =
    xml"""
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="ProjectRootManager" version="2" project-jdk-name=$jdkVersion project-jdk-type="JavaSDK"/>
      </project>
    """.toXml
}
