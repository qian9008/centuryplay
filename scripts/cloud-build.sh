#!/bin/bash

# Century Play Cloud Build Script
# Supports multiple CI/CD platforms and build environments

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PROJECT_NAME="centuryplay"
APP_PACKAGE="com.airplay.streamer"
BUILD_DIR="app/build/outputs/apk"
GRADLE_WRAPPER="./gradlew"

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if running in CI environment
is_ci_environment() {
    [[ -n "$CI" ]] || [[ -n "$GITHUB_ACTIONS" ]] || [[ -n "$GITLAB_CI" ]]
}

# Function to setup environment
setup_environment() {
    print_status "Setting up build environment..."
    
    # Check if we're in a CI environment
    if is_ci_environment; then
        print_status "Detected CI environment"
        
        # Set Java home if not set
        if [[ -z "$JAVA_HOME" ]]; then
            export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
        fi
        
        # Setup Android SDK if needed
        if [[ ! -d "$ANDROID_SDK_ROOT" ]]; then
            print_status "Setting up Android SDK..."
            export ANDROID_SDK_ROOT="/tmp/android-sdk"
            mkdir -p "$ANDROID_SDK_ROOT"
            
            # Download Android SDK if not present
            if [[ ! -f "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
                print_status "Downloading Android SDK..."
                wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/sdk.zip
                unzip -q /tmp/sdk.zip -d /tmp/sdk
                mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
                mv /tmp/sdk/cmdline-tools/* "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
                rm -rf /tmp/sdk /tmp/sdk.zip
            fi
            
            export PATH="$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools"
            
            # Install required components
            print_status "Installing Android SDK components..."
            yes | sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
        fi
    fi
    
    # Make gradlew executable
    chmod +x "$GRADLE_WRAPPER"
    
    print_success "Environment setup completed"
}

# Function to clean previous builds
clean_build() {
    print_status "Cleaning previous builds..."
    "$GRADLE_WRAPPER" clean
    print_success "Clean completed"
}

# Function to build debug APK
build_debug() {
    print_status "Building debug APK..."
    "$GRADLE_WRAPPER" assembleDebug
    print_success "Debug APK built successfully"
    
    if [[ -f "$BUILD_DIR/debug/app-debug.apk" ]]; then
        local size=$(du -h "$BUILD_DIR/debug/app-debug.apk" | cut -f1)
        print_success "Debug APK size: $size"
    fi
}

# Function to build release APK
build_release() {
    print_status "Building release APK..."
    
    # Check if signing configuration is available
    if [[ -n "$SIGNING_KEY_ALIAS" ]] && [[ -n "$SIGNING_KEY_PASSWORD" ]] && [[ -n "$SIGNING_STORE_PASSWORD" ]]; then
        print_status "Signing configuration found, building signed release APK..."
        "$GRADLE_WRAPPER" assembleRelease
        print_success "Signed release APK built successfully"
        
        if [[ -f "$BUILD_DIR/release/app-release.apk" ]]; then
            local size=$(du -h "$BUILD_DIR/release/app-release.apk" | cut -f1)
            print_success "Release APK size: $size"
        fi
    else
        print_warning "No signing configuration found, building unsigned release APK..."
        "$GRADLE_WRAPPER" assembleRelease
        print_success "Unsigned release APK built successfully"
    fi
}

# Function to run tests
run_tests() {
    print_status "Running unit tests..."
    "$GRADLE_WRAPPER" test
    print_success "Unit tests completed"
}

# Function to run lint analysis
run_lint() {
    print_status "Running lint analysis..."
    "$GRADLE_WRAPPER" lint
    print_success "Lint analysis completed"
    
    if [[ -f "app/build/reports/lint-results.html" ]]; then
        print_status "Lint report generated: app/build/reports/lint-results.html"
    fi
}

# Function to generate build info
generate_build_info() {
    print_status "Generating build information..."
    
    local build_info="{
        \"project\": \"$PROJECT_NAME\",
        \"package\": \"$APP_PACKAGE\",
        \"version\": $(grep -o 'versionName = \"[^\"]*\" app/build.gradle.kts | sed 's/versionName = \"//;s/\"//'),
        \"version_code\": $(grep -o 'versionCode = [0-9]*' app/build.gradle.kts | sed 's/versionCode = //'),
        \"build_time\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",
        \"git_commit\": \"$(git rev-parse HEAD 2>/dev/null || echo 'unknown')\",
        \"git_branch\": \"$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')\",
        \"java_version\": \"$(java -version 2>&1 | head -n1)\",
        \"gradle_version\": \"$("$GRADLE_WRAPPER" --version | grep 'Gradle' | awk '{print $2}')\"
    }"
    
    echo "$build_info" > "$BUILD_DIR/build-info.json"
    print_success "Build information saved to build-info.json"
}

# Function to upload artifacts (for CI environments)
upload_artifacts() {
    if is_ci_environment; then
        print_status "Uploading build artifacts..."
        
        # Create artifacts directory
        mkdir -p artifacts
        
        # Copy APKs
        if [[ -f "$BUILD_DIR/debug/app-debug.apk" ]]; then
            cp "$BUILD_DIR/debug/app-debug.apk" artifacts/
            print_status "Debug APK copied to artifacts/"
        fi
        
        if [[ -f "$BUILD_DIR/release/app-release.apk" ]]; then
            cp "$BUILD_DIR/release/app-release.apk" artifacts/
            print_status "Release APK copied to artifacts/"
        fi
        
        # Copy reports
        if [[ -d "app/build/reports" ]]; then
            cp -r app/build/reports artifacts/
            print_status "Build reports copied to artifacts/"
        fi
        
        # Copy build info
        if [[ -f "$BUILD_DIR/build-info.json" ]]; then
            cp "$BUILD_DIR/build-info.json" artifacts/
            print_status "Build info copied to artifacts/"
        fi
        
        print_success "Artifacts uploaded to artifacts/ directory"
    fi
}

# Function to print build summary
print_summary() {
    print_status "Build Summary:"
    echo "=================="
    
    if [[ -f "$BUILD_DIR/debug/app-debug.apk" ]]; then
        echo "✅ Debug APK: $BUILD_DIR/debug/app-debug.apk"
    fi
    
    if [[ -f "$BUILD_DIR/release/app-release.apk" ]]; then
        echo "✅ Release APK: $BUILD_DIR/release/app-release.apk"
    fi
    
    if [[ -d "app/build/reports" ]]; then
        echo "✅ Build Reports: app/build/reports/"
    fi
    
    if [[ -f "$BUILD_DIR/build-info.json" ]]; then
        echo "✅ Build Info: $BUILD_DIR/build-info.json"
    fi
    
    echo "=================="
    print_success "Cloud build process completed successfully!"
}

# Main build function
main() {
    local build_type=${1:-"all"}
    
    print_status "Starting Century Play cloud build..."
    print_status "Build type: $build_type"
    
    # Setup environment
    setup_environment
    
    # Clean previous builds
    clean_build
    
    # Build based on type
    case "$build_type" in
        "debug")
            build_debug
            ;;
        "release")
            build_release
            ;;
        "test")
            run_tests
            ;;
        "lint")
            run_lint
            ;;
        "all")
            build_debug
            build_release
            run_tests
            run_lint
            ;;
        *)
            print_error "Unknown build type: $build_type"
            echo "Usage: $0 [debug|release|test|lint|all]"
            exit 1
            ;;
    esac
    
    # Generate build info
    generate_build_info
    
    # Upload artifacts (in CI environments)
    upload_artifacts
    
    # Print summary
    print_summary
}

# Parse command line arguments
if [[ $# -gt 0 ]]; then
    main "$@"
else
    main "all"
fi
