package seed.artefact

import java.nio.file.Path

import seed.Cli.PackageConfig
import MavenCentral.{CompilerVersion, PlatformVersion}
import seed.cli.util.Ansi
import seed.model.Build.{Dep, JavaDep, Module, ScalaDep}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.{Artefact, Build, Platform, Resolution}
import seed.Log
import seed.config.BuildConfig

object ArtefactResolution {
  def javaDepFromScalaDep(dep: ScalaDep,
                          platform: Platform,
                          platformVersion: PlatformVersion,
                          compilerVersion: CompilerVersion
                         ): JavaDep =
    JavaDep(
      dep.organisation,
      MavenCentral.formatArtefactName(
        dep.artefact, dep.versionTag, platform, platformVersion, compilerVersion
      ), dep.version)

  def javaDepFromArtefact(artefact: Artefact,
                          version: String,
                          platform: Platform,
                          platformVersion: PlatformVersion,
                          compilerVersion: CompilerVersion
                         ): JavaDep =
    JavaDep(
      artefact.organisation,
      artefact.versionTag.fold(artefact.name)(vt =>
        MavenCentral.formatArtefactName(artefact.name, vt, platform,
          platformVersion, compilerVersion)
      ), version)

  def jsPlatformDeps(build: Build, module: Module): Set[JavaDep] = {
    val scalaVersion   = BuildConfig.scalaVersion(build.project, List(module))
    val scalaJsVersion = build.project.scalaJsVersion.get

    Set(
      Artefact.ScalaJsCompiler,
      Artefact.ScalaJsLibrary
    ).map(artefact =>
      javaDepFromArtefact(artefact, scalaJsVersion, JavaScript,
        scalaJsVersion, scalaVersion)
    )
  }

  def nativePlatformDeps(build: Build, module: Module): Set[JavaDep] = {
    val scalaVersion = BuildConfig.scalaVersion(build.project, List(module))
    val scalaNativeVersion = build.project.scalaNativeVersion.get

    Set(
      Artefact.ScalaNativePlugin,
      Artefact.ScalaNativeJavalib,
      Artefact.ScalaNativeScalalib,
      Artefact.ScalaNativeNativelib,
      Artefact.ScalaNativeAuxlib
    ).map(artefact =>
      javaDepFromArtefact(artefact, scalaNativeVersion, Native,
        scalaNativeVersion, scalaVersion)
    )
  }

  def jvmArtefacts(stack: List[Module]): Set[(Platform, Dep)] =
    stack.flatMap(_.scalaDeps).map(dep => JVM -> dep).toSet ++
    stack.flatMap(_.javaDeps).map(dep => JVM -> dep).toSet

  def jvmDeps(build: Build, stack: List[Module]): Set[JavaDep] = {
    val scalaVersion = BuildConfig.scalaVersion(build.project, stack)

    stack.flatMap(_.scalaDeps).map(dep =>
      javaDepFromScalaDep(dep, JVM, scalaVersion, scalaVersion)
    ).toSet ++
    stack.flatMap(_.javaDeps).toSet
  }

  def jsArtefacts(stack: List[Module]): Set[(Platform, Dep)] =
    stack.flatMap(_.scalaDeps).map(dep => JavaScript -> dep).toSet

  def jsDeps(build: Build, stack: List[Module]): Set[JavaDep] =
    build.project.scalaJsVersion match {
      case None                 => Set()
      case Some(scalaJsVersion) =>
        val scalaVersion = BuildConfig.scalaVersion(build.project, stack)
        stack.flatMap(_.scalaDeps).map(dep =>
          javaDepFromScalaDep(dep, JavaScript, scalaJsVersion, scalaVersion)
        ).toSet
    }

  def nativeArtefacts(stack: List[Module]): Set[(Platform, Dep)] =
    stack.flatMap(_.scalaDeps).map(dep => Native -> dep).toSet

  def nativeDeps(build: Build, stack: List[Module]): Set[JavaDep] =
    build.project.scalaNativeVersion match {
      case None                     => Set()
      case Some(scalaNativeVersion) =>
        val scalaVersion = BuildConfig.scalaVersion(build.project, stack)
        stack.flatMap(_.scalaDeps).map(dep =>
          javaDepFromScalaDep(dep, Native, scalaNativeVersion, scalaVersion)
        ).toSet
    }

  def compilerDeps(build: Build, module: Module): List[Set[JavaDep]] = {
    def f(build: Build, module: Module): Set[JavaDep] = {
      import build.project.scalaOrganisation
      val scalaVersion = BuildConfig.scalaVersion(build.project, List(module))

      Set(
        Artefact.scalaCompiler(scalaOrganisation),
        Artefact.scalaLibrary(scalaOrganisation),
        Artefact.scalaReflect(scalaOrganisation)
      ).map(artefact =>
        javaDepFromArtefact(artefact, scalaVersion, JVM, scalaVersion,
          scalaVersion))
    }

    List(
      f(build, module),
      module.jvm.toSet.flatMap(module => f(build, module)),
      module.js.toSet.flatMap(module => f(build, module)),
      module.native.toSet.flatMap(module => f(build, module))
    ).filter(_.nonEmpty)
  }

  def allCompilerDeps(build: Build): List[Set[JavaDep]] =
    build.module.values.toList.flatMap(compilerDeps(build, _)).distinct

  def platformDeps(build: Build, module: Module): Set[JavaDep] =
    module.targets.toSet[Platform].flatMap { target =>
      if (target == JavaScript)
        jsPlatformDeps(build, module.js.getOrElse(Module()))
      else if (target == Native) nativePlatformDeps(build,
        module.native.getOrElse(Module()))
      else Set[JavaDep]()
    }

  def libraryDeps(build: Build,
                  module: Module,
                  platforms: Set[Platform],
                  parent: Module = Module()
                 ): Set[JavaDep] = {
    val targets = if (module.targets.isEmpty) parent.targets else module.targets
    targets.toSet[Platform].intersect(platforms).flatMap { target =>
      // Shared libraries
      if (target == JVM)
        jvmDeps(build,
          module.jvm.toList ++ parent.jvm.toList ++ List(module, parent))
      else if (target == JavaScript)
        jsDeps(build,
          module.js.toList ++ parent.js.toList ++ List(module, parent))
      else nativeDeps(build,
        module.native.toList ++ parent.native.toList ++ List(module, parent))
    } ++
    (if (!platforms.contains(JVM)) Set()
     else module.jvm.toSet.flatMap(jvm => jvmDeps(build,
       List(jvm, parent.jvm.getOrElse(Module()), module)))
    ) ++
    (if (!platforms.contains(JavaScript)) Set()
     else module.js.toSet.flatMap(js => jsDeps(build,
       List(js, parent.js.getOrElse(Module()), module)))
    ) ++
    (if (!platforms.contains(Native)) Set()
     else module.native.toSet.flatMap(native => nativeDeps(build,
       List(native, parent.native.getOrElse(Module()), module)))
    ) ++
    module.test.toSet.flatMap(libraryDeps(build, _, platforms, module))
  }

  def libraryArtefacts(build: Build,
                       module: Module,
                       parent: Module = Module()
                      ): Set[(Platform, Dep)] =
    module.targets.toSet[Platform].flatMap { target =>
      if (target == JVM) jvmArtefacts(List(module, parent))
      else if (target == JavaScript) jsArtefacts(List(module, parent))
      else nativeArtefacts(List(module, parent))
    } ++
    module.jvm.toSet.flatMap(jvm =>
      jvmArtefacts(List(jvm, parent.jvm.getOrElse(Module()), module))) ++
    module.js.toSet.flatMap(js =>
      jsArtefacts(List(js, parent.js.getOrElse(Module()), module))) ++
    module.native.toSet.flatMap(native =>
      nativeArtefacts(
        List(native, parent.native.getOrElse(Module()), module))) ++
    module.test.toSet.flatMap(libraryArtefacts(build, _, module))

  def allPlatformDeps(build: Build): Set[JavaDep] =
    build.module.values.toSet.flatMap(platformDeps(build, _))

  def allLibraryDeps(build: Build,
                     platforms: Set[Platform] = Set(JVM, JavaScript, Native)
                    ): Set[JavaDep] =
    build.module.values.toSet.flatMap(libraryDeps(build, _, platforms))

  def allLibraryArtefacts(build: Build): Map[Platform, Set[Dep]] =
    build.module.values.toSet.flatMap(libraryArtefacts(build, _))
      .groupBy(_._1)
      .mapValues(_.map(_._2))

  def resolveScalaCompiler(resolutionResult: List[Coursier.ResolutionResult],
                           scalaOrganisation: String,
                           scalaVersion: String,
                           classPath: List[Path]
                          ): Resolution.ScalaCompiler = {
    val compilerDep = JavaDep(scalaOrganisation, "scala-compiler", scalaVersion)
    val libraryDep  = JavaDep(scalaOrganisation, "scala-library", scalaVersion)
    val reflectDep  = JavaDep(scalaOrganisation, "scala-reflect", scalaVersion)

    val resolution = resolutionResult.find(r =>
      Coursier.hasDep(r, compilerDep)).get

    val compiler = Coursier.localArtefacts(resolution, Set(compilerDep))
    val libraries =
      Coursier.localArtefacts(resolution, Set(libraryDep, reflectDep))

    // Replace official scala-library and scala-reflect artefacts by
    // organisation-specific ones. This is needed for Typelevel Scala.
    val fullClassPath = libraries.map(_.libraryJar) ++
                        classPath
                          .filter(!_.toString.contains("/scala-library/"))
                          .filter(!_.toString.contains("/scala-reflect/"))

    val compilerJars: List[Path] =
      compiler.map(_.libraryJar)
        .filter(!_.toString.contains("/scala-library/"))
        .filter(!_.toString.contains("/scala-reflect/")) ++
      libraries.map(_.libraryJar)

    Resolution.ScalaCompiler(
      scalaOrganisation, scalaVersion, fullClassPath.distinct, compilerJars)
  }

  def resolution(seedConfig: seed.model.Config,
                 build: Build,
                 packageConfig: PackageConfig,
                 optionalArtefacts: Boolean,
                 platformDeps: Set[JavaDep],
                 compilerDeps: List[Set[JavaDep]],
                ) = {
    val silent = packageConfig.silent || seedConfig.resolution.silent
    if (!silent) Coursier.initLogger()

    import packageConfig._
    val resolvedIvyPath = ivyPath.getOrElse(seedConfig.resolution.ivyPath)
    val resolvedCachePath = cachePath.getOrElse(seedConfig.resolution.cachePath)

    Log.info("Configured resolvers:")
    Log.info("  - " + resolvedIvyPath + " (Ivy)")
    Log.info("  - " + resolvedCachePath + " (Coursier)")
    build.resolvers.ivy.foreach(ivy => Log.info("  - " + Ansi.italic(ivy.url) + " (Ivy)"))
    build.resolvers.maven.foreach(maven => Log.info("  - " + Ansi.italic(maven) + " (Maven)"))

    def resolve(deps: Set[JavaDep]) =
      Coursier.resolveAndDownload(deps, build.resolvers, resolvedIvyPath,
        resolvedCachePath, optionalArtefacts)

    Log.info("Resolving platform artefacts...")

    val platformResolution = resolve(platformDeps)

    Log.info("Resolving compiler artefacts...")

    // Resolve Scala compilers separately because Coursier merges dependencies
    // with different versions
    val compilerResolution = compilerDeps.map(resolve)

    (resolvedCachePath, platformResolution, compilerResolution)
  }
}
