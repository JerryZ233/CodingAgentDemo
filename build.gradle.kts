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
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
