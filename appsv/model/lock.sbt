// DON'T EDIT THIS FILE.
// This file is auto generated by sbt-lock 0.8.0.
// https://github.com/tkawachi/sbt-lock/
Compile / dependencyOverrides ++= {
  if (!(ThisBuild / sbtLockHashIsUpToDate).value && sbtLockIgnoreOverridesOnStaleHash.value) {
    Seq.empty
  } else {
    Seq(
      "com.fasterxml.jackson.core" % "jackson-annotations" % "2.14.3",
      "com.fasterxml.jackson.core" % "jackson-core" % "2.14.3",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.14.3",
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % "2.14.3",
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % "2.14.3",
      "com.google.errorprone" % "error_prone_annotations" % "2.36.0",
      "com.google.guava" % "failureaccess" % "1.0.3",
      "com.google.guava" % "guava" % "33.4.8-jre",
      "com.google.guava" % "listenablefuture" % "9999.0-empty-to-avoid-conflict-with-guava",
      "com.google.j2objc" % "j2objc-annotations" % "3.0.0",
      "com.lambdaworks" % "scrypt" % "1.4.0",
      "com.sun.activation" % "jakarta.activation" % "1.2.1",
      "com.sun.mail" % "jakarta.mail" % "1.6.7",
      "commons-beanutils" % "commons-beanutils" % "1.9.4",
      "commons-codec" % "commons-codec" % "1.18.0",
      "commons-collections" % "commons-collections" % "3.2.2",
      "commons-digester" % "commons-digester" % "2.1",
      "commons-io" % "commons-io" % "2.18.0",
      "commons-logging" % "commons-logging" % "1.3.2",
      "commons-validator" % "commons-validator" % "1.9.0",
      "org.apache.commons" % "commons-email" % "1.6.0",
      "org.apache.tika" % "tika-core" % "2.9.3",
      "org.jspecify" % "jspecify" % "1.0.0",
      "org.owasp.encoder" % "encoder" % "1.3.1",
      "org.playframework" % "play-functional_2.13" % "3.0.4",
      "org.playframework" % "play-json_2.13" % "3.0.4",
      "org.scalactic" % "scalactic_2.13" % "3.2.19",
      "org.slf4j" % "slf4j-api" % "2.0.16"
    )
  }
}
// LIBRARY_DEPENDENCIES_HASH 6651d2090fd2e32ef7ef61c57ff378694e79c307
