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
    systemProperty("coding.agent.log.dir", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
    systemProperty("coding.agent.test.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
