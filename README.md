# Android native vpn plugin

Modify your `app/build.gradle` to use abiFilter since flutter doesn't apply abiFilter for target platform yet.
```gradle
android {
    ...
    buildTypes {
        ...
        release {
            ...
            ndk {
                if (!project.hasProperty('target-platform')) {
                    abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
                } else {
                    def platforms = project.property('target-platform').split(',')
                    def platformMap = [
                            'android-arm'  : 'armeabi-v7a',
                            'android-arm64': 'arm64-v8a',
                            'android-x86'  : 'x86',
                            'android-x64'  : 'x86_64',
                    ]
                    abiFilters = platforms.stream().map({ e ->
                        platformMap.containsKey(e) ? platformMap[e] : e
                    }).toArray()
                }
            }
    }
}
```

