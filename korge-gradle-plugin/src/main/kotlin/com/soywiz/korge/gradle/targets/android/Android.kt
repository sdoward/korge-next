package com.soywiz.korge.gradle.targets.android

import com.soywiz.korge.gradle.util.*
import com.soywiz.korge.gradle.*
import com.soywiz.korge.gradle.targets.GROUP_KORGE_INSTALL
import com.soywiz.korge.gradle.targets.GROUP_KORGE_RUN
import com.soywiz.korge.gradle.targets.getIconBytes
import com.soywiz.korge.gradle.targets.getResourceBytes
import com.soywiz.korge.gradle.util.*
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.GradleBuild
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.io.File
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

//Linux: ~/Android/Sdk
//Mac: ~/Library/Android/sdk
//Windows: %LOCALAPPDATA%\Android\sdk
val Project.androidSdkPath: String get() {
    val localPropertiesFile = projectDir["local.properties"]
    if (localPropertiesFile.exists()) {
        val props = Properties().apply { load(localPropertiesFile.readText().reader()) }
        if (props.getProperty("sdk.dir") != null) {
            return props.getProperty("sdk.dir")!!
        }
    }
    val userHome = System.getProperty("user.home")
    return listOfNotNull(
        System.getenv("ANDROID_HOME"),
        "$userHome/AppData/Local/Android/sdk",
        "$userHome/Library/Android/sdk",
        "$userHome/Android/Sdk"
    ).firstOrNull { File(it).exists() } ?: error("Can't find android sdk (ANDROID_HOME environment not set and Android SDK not found in standard locations)")
}

fun Project.configureNativeAndroid() {
	val resolvedKorgeArtifacts = LinkedHashMap<String, String>()
	val resolvedOtherArtifacts = LinkedHashMap<String, String>()
	val resolvedModules = LinkedHashMap<String, String>()

	val parentProjectName = parent?.name
	val allModules: Map<String, Project> = parent?.childProjects?.filter { (_, u) ->
		name != u.name
	}.orEmpty()
	val topLevelDependencies = mutableListOf<String>()

	configurations.all { conf ->
		if (conf.attributes.getAttribute(KotlinPlatformType.attribute)?.name == "jvm") {
			conf.resolutionStrategy.eachDependency { dep ->
				if (topLevelDependencies.isEmpty() && !conf.name.removePrefix("jvm").startsWith("Test")) {
					topLevelDependencies.addAll(conf.incoming.dependencies.map { "${it.group}:${it.name}" })
				}
				val cleanFullName = "${dep.requested.group}:${dep.requested.name}"
				//println("RESOLVE ARTIFACT: ${it.requested}")
				//if (cleanFullName.startsWith("org.jetbrains.intellij.deps:trove4j")) return@eachDependency
				//if (cleanFullName.startsWith("org.jetbrains:annotations")) return@eachDependency
				if (cleanFullName.startsWith("org.jetbrains")) return@eachDependency
				if (cleanFullName.startsWith("junit:junit")) return@eachDependency
				if (cleanFullName.startsWith("org.hamcrest:hamcrest-core")) return@eachDependency
				if (cleanFullName.startsWith("org.jogamp")) return@eachDependency
				if (cleanFullName.contains("-metadata")) return@eachDependency
				if (dep.requested.group == parentProjectName && allModules.contains(dep.requested.name))
					resolvedModules[dep.requested.name] = ":${parentProjectName}:${dep.requested.name}"
				else if (cleanFullName.startsWith("com.soywiz.korlibs."))
					resolvedKorgeArtifacts[cleanFullName.removeSuffix("-jvm")] = dep.requested.version.toString()
				else if (topLevelDependencies.contains(cleanFullName)) {
					resolvedOtherArtifacts[cleanFullName] = dep.requested.version.toString()
				}
			}
		}
	}

	//val androidPackageName = "com.example.myapplication"
	//val androidAppName = "My Awesome APP Name"
	val prepareAndroidBootstrap = tasks.create("prepareAndroidBootstrap") { task ->
		task.dependsOn("compileTestKotlinJvm") // So artifacts are resolved
		task.apply {
			val overwrite = korge.overwriteAndroidFiles
			val outputFolder = File(buildDir, "platforms/android")
			doLast {
				val androidPackageName = korge.id
				val androidAppName = korge.name

				val DOLLAR = "\\$"
				val ifNotExists = !overwrite
				//File(outputFolder, "build.gradle").conditionally(ifNotExists) {
				//	ensureParents().writeText("""
				//		// Top-level build file where you can add configuration options common to all sub-projects/modules.
				//		buildscript {
				//			repositories { google(); jcenter() }
				//			dependencies { classpath 'com.android.tools.build:gradle:3.3.0'; classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion" }
				//		}
				//		allprojects {
				//			repositories {
				//				mavenLocal(); maven { url = "https://dl.bintray.com/korlibs/korlibs" }; google(); jcenter()
				//			}
				//		}
				//		task clean(type: Delete) { delete rootProject.buildDir }
				//""".trimIndent())
				//}
				File(outputFolder, "local.properties").conditionally(ifNotExists) {
					ensureParents().writeTextIfChanged("sdk.dir=${androidSdkPath.escape()}")
				}
				//File(outputFolder, "settings.gradle").conditionally(ifNotExists) {
                File(outputFolder, "settings.gradle").always {
					ensureParents().writeTextIfChanged(Indenter {
						line("enableFeaturePreview(\"GRADLE_METADATA\")")
                        line("rootProject.name = ${project.name.quoted}")
						if (parentProjectName != null && resolvedModules.isNotEmpty()) this@configureNativeAndroid.parent?.projectDir?.let {
							line("include(\":$parentProjectName\")")
							line("project(\":$parentProjectName\").projectDir = file(\'$it\')")
							resolvedModules.forEach { (name, path) ->
								line("include(\"$path\")")
								line("project(\"$path\").projectDir = file(\'$it/$name\')")
							}
						}
					})
				}
				File(
					outputFolder,
					"proguard-rules.pro"
				).conditionally(ifNotExists) { ensureParents().writeTextIfChanged("#Rules here\n") }

				outputFolder["gradle"].mkdirs()
				rootDir["gradle"].copyRecursively(outputFolder["gradle"], overwrite = true) { f, e -> OnErrorAction.SKIP }

                File(outputFolder, "build.extra.gradle").conditionally(ifNotExists) {
                    ensureParents().writeTextIfChanged(Indenter {
                        line("")
                    })
                }

				File(outputFolder, "build.gradle").always {
					ensureParents().writeTextIfChanged(Indenter {
						line("buildscript") {
							line("repositories { google(); jcenter(); maven { url = uri(\"https://dl.bintray.com/kotlin/kotlin-eap\") }; maven { url = uri(\"https://dl.bintray.com/kotlin/kotlin-dev\") } }")
							line("dependencies { classpath 'com.android.tools.build:gradle:$androidBuildGradleVersion'; classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion' }")
						}
						line("repositories") {
							line("mavenLocal()")
							line("maven { url = 'https://dl.bintray.com/korlibs/korlibs' }")
							line("google()")
							line("jcenter()")
                            line("maven { url = uri(\"https://dl.bintray.com/kotlin/kotlin-eap\") }")
							line("maven { url = uri(\"https://dl.bintray.com/kotlin/kotlin-dev\") }")
						}

						if (korge.androidLibrary) {
							line("apply plugin: 'com.android.library'")
						} else {
							line("apply plugin: 'com.android.application'")
						}
						line("apply plugin: 'kotlin-android'")
						line("apply plugin: 'kotlin-android-extensions'")

						line("android") {
                            line("kotlinOptions") {
                                line("jvmTarget = \"1.8\"")
                            }
                            line("packagingOptions") {
                                line("exclude 'META-INF/DEPENDENCIES'")
                                line("exclude 'META-INF/LICENSE'")
                                line("exclude 'META-INF/LICENSE.txt'")
                                line("exclude 'META-INF/license.txt'")
                                line("exclude 'META-INF/NOTICE'")
                                line("exclude 'META-INF/NOTICE.txt'")
                                line("exclude 'META-INF/notice.txt'")
                                line("exclude 'META-INF/*.kotlin_module'")
								line("exclude '**/*.kotlin_metadata'")
								line("exclude '**/*.kotlin_builtins'")
                            }
							line("compileSdkVersion ${korge.androidCompileSdk}")
							line("defaultConfig") {
								if (korge.androidMinSdk < 21)
									line("multiDexEnabled true")

								if (!korge.androidLibrary) {
									line("applicationId '$androidPackageName'")
								}

								line("minSdkVersion ${korge.androidMinSdk}")
								line("targetSdkVersion ${korge.androidTargetSdk}")
								line("versionCode 1")
								line("versionName '1.0'")
//								line("buildConfigField 'boolean', 'FULLSCREEN', '${korge.fullscreen}'")
								line("testInstrumentationRunner 'android.support.test.runner.AndroidJUnitRunner'")
                                val manifestPlaceholdersStr = korge.configs.map { it.key + ":" + it.value.quoted }.joinToString(", ")
								line("manifestPlaceholders = ${if (manifestPlaceholdersStr.isEmpty()) "[:]" else "[$manifestPlaceholdersStr]" }")
							}
							line("signingConfigs") {
								line("release") {
									line("storeFile file(findProperty('RELEASE_STORE_FILE') ?: 'korge.keystore')")
									line("storePassword findProperty('RELEASE_STORE_PASSWORD') ?: 'password'")
									line("keyAlias findProperty('RELEASE_KEY_ALIAS') ?: 'korge'")
									line("keyPassword findProperty('RELEASE_KEY_PASSWORD') ?: 'password'")
								}
							}
							line("buildTypes") {
								line("debug") {
									line("minifyEnabled false")
									line("signingConfig signingConfigs.release")
								}
								line("release") {
									//line("minifyEnabled false")
									line("minifyEnabled true")
									line("proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'")
									line("signingConfig signingConfigs.release")
								}
							}
							line("sourceSets") {
								line("main") {
									// @TODO: Use proper source sets of the app

									val projectDir = project.projectDir
									line("java.srcDirs += [${"$projectDir/src/commonMain/kotlin".quoted}, ${"$projectDir/src/jvmMain/kotlin".quoted}]")
									line("assets.srcDirs += [${"$projectDir/src/commonMain/resources".quoted}, ${"$projectDir/build/genMainResources".quoted}]")
								}
							}
						}

						line("dependencies") {
							line("implementation fileTree(dir: 'libs', include: ['*.jar'])")

							if (parentProjectName != null) {
								for ((_, path) in resolvedModules) {
									line("implementation project(\'$path\')")
								}
							}

							line("implementation 'org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion'")
							if (korge.androidMinSdk < 21)
								line("implementation 'com.android.support:multidex:1.0.3'")

							//line("api 'org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion'")
							for ((name, version) in resolvedKorgeArtifacts) {
//								if (name.startsWith("org.jetbrains.kotlin")) continue
//								if (name.contains("-metadata")) continue
                                //if (name.startsWith("com.soywiz.korlibs.krypto:krypto")) continue
                                //if (name.startsWith("com.soywiz.korlibs.korge:korge")) {
								val rversion = getModuleVersion(name, version)
								line("implementation '$name-android:$rversion'")
							}

							for ((name, version) in resolvedOtherArtifacts) {
								line("implementation '$name:$version'")
							}

							for (dependency in korge.plugins.pluginExts.getAndroidDependencies()) {
								line("implementation ${dependency.quoted}")
							}

							line("implementation 'com.android.support:appcompat-v7:28.0.0'")
							line("implementation 'com.android.support.constraint:constraint-layout:1.1.3'")
							line("testImplementation 'junit:junit:4.12'")
							line("androidTestImplementation 'com.android.support.test:runner:1.0.2'")
							line("androidTestImplementation 'com.android.support.test.espresso:espresso-core:3.0.2'")
						}

						line("configurations") {
							line("androidTestImplementation.extendsFrom(commonMainApi)")
						}

                        line("apply from: 'build.extra.gradle'")
					}.toString())
				}

				writeAndroidManifest(outputFolder, korge)

				File(outputFolder, "gradle.properties").conditionally(ifNotExists) {
					ensureParents().writeTextIfChanged("org.gradle.jvmargs=-Xmx1536m")
				}
			}
		}
	}

	val bundleAndroid = tasks.create("bundleAndroid", GradleBuild::class.java) { task ->
		task.apply {
			group = GROUP_KORGE_INSTALL
			dependsOn(prepareAndroidBootstrap)
			buildFile = File(buildDir, "platforms/android/build.gradle")
			version = "4.10.1"
			tasks = listOf("bundleDebugAar")
		}
	}

	val buildAndroidAar = tasks.create("buildAndroidAar", GradleBuild::class.java) { task ->
		task.dependsOn(bundleAndroid)
	}

	// adb shell am start -n com.package.name/com.package.name.ActivityName
	for (debug in listOf(false, true)) {
		val suffixDebug = if (debug) "Debug" else "Release"
		val installAndroidTask = tasks.create("installAndroid$suffixDebug", GradleBuild::class.java) { task ->
			task.apply {
				group = GROUP_KORGE_INSTALL
				dependsOn(prepareAndroidBootstrap)
				buildFile = File(buildDir, "platforms/android/build.gradle")
				version = "4.10.1"
				tasks = listOf("install$suffixDebug")
			}
		}

		for (emulator in listOf(null, false, true)) {
			val suffixDevice = when (emulator) {
				null -> ""
				false -> "Device"
				true -> "Emulator"
			}

			val extra = when (emulator) {
				null -> arrayOf()
				false -> arrayOf("-d")
				true -> arrayOf("-e")
			}

			tasks.createTyped<DefaultTask>("runAndroid$suffixDevice$suffixDebug") {
				group = GROUP_KORGE_RUN
				dependsOn(installAndroidTask)
				doFirst {
					execLogger {
						it.commandLine(
							"$androidSdkPath/platform-tools/adb", *extra, "shell", "am", "start", "-n",
							"${korge.id}/${korge.id}.MainActivity"
						)
					}
				}
			}
		}
	}
}

fun writeAndroidManifest(outputFolder: File, korge: KorgeExtension) {
	val androidPackageName = korge.id
	val androidAppName = korge.name
	val ifNotExists = korge.overwriteAndroidFiles
	File(outputFolder, "src/main/AndroidManifest.xml").also { it.parentFile.mkdirs() }.conditionally(ifNotExists) {
		ensureParents().writeTextIfChanged(Indenter {
			line("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
			line("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$androidPackageName\">")
			indent {
				line("<application")
				indent {
					line("")
					line("android:allowBackup=\"true\"")

					if (!korge.androidLibrary) {
						line("android:label=\"$androidAppName\"")
						line("android:icon=\"@mipmap/icon\"")
						// // line("android:icon=\"@android:drawable/sym_def_app_icon\"")
						line("android:roundIcon=\"@android:drawable/sym_def_app_icon\"")
						line("android:theme=\"@android:style/Theme.Holo.NoActionBar\"")
					}


					line("android:supportsRtl=\"true\"")
				}
				line(">")
				indent {
					for (text in korge.plugins.pluginExts.getAndroidManifestApplication()) {
						line(text)
					}
					for (text in korge.androidManifestApplicationChunks) {
						line(text)
					}

					line("<activity android:name=\".MainActivity\"")
					indent {
						when (korge.orientation) {
							Orientation.LANDSCAPE -> line("android:screenOrientation=\"landscape\"")
							Orientation.PORTRAIT -> line("android:screenOrientation=\"portrait\"")
                            Orientation.DEFAULT -> Unit
						}
					}
					line(">")

					if (!korge.androidLibrary) {
						indent {
							line("<intent-filter>")
							indent {
								line("<action android:name=\"android.intent.action.MAIN\"/>")
								line("<category android:name=\"android.intent.category.LAUNCHER\"/>")
							}
							line("</intent-filter>")
						}
					}
					line("</activity>")
				}
				line("</application>")
				for (text in korge.androidManifestChunks) {
					line(text)
				}
			}
			line("</manifest>")
		}.toString())
	}
	File(outputFolder, "korge.keystore").conditionally(ifNotExists) {
		ensureParents().writeBytesIfChanged(getResourceBytes("korge.keystore"))
	}
	File(outputFolder, "src/main/res/mipmap-mdpi/icon.png").conditionally(ifNotExists) {
		ensureParents().writeBytesIfChanged(korge.getIconBytes())
	}
	File(outputFolder, "src/main/java/MainActivity.kt").conditionally(ifNotExists) {
		ensureParents().writeTextIfChanged(Indenter {
			line("package $androidPackageName")

			line("import com.soywiz.korio.android.withAndroidContext")
			line("import com.soywiz.korgw.KorgwActivity")
			line("import ${korge.realEntryPoint}")

			line("class MainActivity : KorgwActivity()") {
				line("override suspend fun activityMain()") {
					//line("withAndroidContext(this)") { // @TODO: Probably we should move this to KorgwActivity itself
						for (text in korge.plugins.pluginExts.getAndroidInit()) {
							line(text)
						}
						line("${korge.realEntryPoint}()")
					//}
				}
			}
		}.toString())
	}
}

private var _tryAndroidSdkDirs: List<File>? = null
val tryAndroidSdkDirs: List<File> get() {
    if (_tryAndroidSdkDirs == null) {
        _tryAndroidSdkDirs = listOf(
            File(System.getProperty("user.home"), "/Library/Android/sdk"), // MacOS
            File(System.getProperty("user.home"), "/Android/Sdk"), // Linux
            File(System.getProperty("user.home"), "/AppData/Local/Android/Sdk") // Windows
        )
    }
    return _tryAndroidSdkDirs!!
}

val prop_sdk_dir: String? get() = System.getProperty("sdk.dir")
val prop_ANDROID_HOME: String? get() = System.getenv("ANDROID_HOME")
private var _hasAndroidConfigured: Boolean? = null
var hasAndroidConfigured: Boolean
    set(value) {
        _hasAndroidConfigured = value
    }
    get() {
        if (_hasAndroidConfigured == null) {
            _hasAndroidConfigured = ((prop_sdk_dir != null) || (prop_ANDROID_HOME != null))
        }
        return _hasAndroidConfigured!!
    }

fun Project.tryToDetectAndroidSdkPath(): File? {
	for (tryAndroidSdkDirs in tryAndroidSdkDirs) {
		if (tryAndroidSdkDirs.exists()) {
			return tryAndroidSdkDirs.absoluteFile
		}
	}
	return null
}
