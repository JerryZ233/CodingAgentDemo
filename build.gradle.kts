plugins {
    id("java")
    id("application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.demo.cli.Main")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-api:2.26.1")
    // Programmatic bootstrap and appender health checks use Core APIs.
    implementation("org.apache.logging.log4j:log4j-core:2.26.1")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1")
    implementation("org.jline:jline-reader:3.30.6")
    implementation("org.jline:jline-terminal:3.30.6")
    runtimeOnly("org.jline:jline-terminal-jni:3.30.6")
    // Model API client (owns HTTP transport and protocol serialization)
    implementation("com.openai:openai-java:4.81.0")
    
    // YAML parsing
    implementation("org.yaml:snakeyaml:2.2")
    
    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    val testTemp = layout.buildDirectory.dir("test-temp")
    doFirst { testTemp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", testTemp.get().asFile.absolutePath)
    systemProperty("junit.jupiter.tempdir.factory.default", "com.demo.testing.RetryingTempDirectories")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    systemProperty("coding.agent.log.dir", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
    systemProperty("coding.agent.test.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Deterministic memory acceptance only: scripted clients/local HTTP, no paid model evaluations.
tasks.register<Test>("memoryTest") {
    description = "Runs isolated layered-memory acceptance tests without real model APIs"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("com/demo/memory/**", "com/demo/session/*Memory*Test.class",
            "com/demo/session/AutomaticCompactionIntegrationTest.class", "com/demo/session/HistoryRetrievalIntegrationTest.class",
            "com/demo/session/ContextPreparationIntegrationTest.class", "com/demo/cli/*Memory*Test.class",
            "com/demo/llm/ObservedLLMClientTest.class", "com/demo/llm/impl/OpenAICompatibleLLMClientTest.class")
    val testTemp = layout.buildDirectory.dir("memory-test-temp")
    doFirst { testTemp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", testTemp.get().asFile.absolutePath)
    systemProperty("junit.jupiter.tempdir.factory.default", "com.demo.testing.RetryingTempDirectories")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    systemProperty("coding.agent.log.dir", layout.buildDirectory.dir("memory-test-logs").get().asFile.absolutePath)
    systemProperty("coding.agent.test.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
