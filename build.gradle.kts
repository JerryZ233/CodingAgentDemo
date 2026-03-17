plugins {
    id("java")
    id("application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.demo.agent.Main")
}

repositories {
    mavenCentral()
}

dependencies {
    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
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
