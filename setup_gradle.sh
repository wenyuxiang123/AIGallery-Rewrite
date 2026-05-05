#!/bin/bash
# Gradle wrapper setup script

# Download gradle wrapper jar
echo "Downloading Gradle wrapper..."
curl -sL -o gradle/wrapper/gradle-wrapper.jar \
    "https://raw.githubusercontent.com/nicoulaj/gradle-wrapper/main/gradle-8.2/gradle-wrapper.jar" \
    || curl -sL -o gradle/wrapper/gradle-wrapper.jar \
    "https://github.com/AIGallery-Rewrite/gradle-wrapper/raw/main/8.2/gradle-wrapper.jar"

echo "Setup complete. Run ./gradlew assembleDebug to build."
