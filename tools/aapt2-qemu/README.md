# ARM64 上跑 aapt2（qemu 包装）

AGP 只分发 x86_64 版 aapt2，ARM64 机器上直接跑会报
`AAPT2 Daemon #0: Daemon startup failed`（ELF 解释器 `/lib64/ld-linux-x86-64.so.2` 不存在）。

用法：

```sh
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$PWD/tools/aapt2-qemu/aapt2
```

前置依赖：

```sh
dpkg --add-architecture amd64 && apt-get update
apt-get install -y qemu-user-binfmt libc6:amd64 libstdc++6:amd64 zlib1g:amd64
```

注意：AGP 要求该路径的**文件名就叫 `aapt2`**，改成别的名字会被判「不是 AAPT2 可执行文件」。

x86_64 CI（GitHub Actions ubuntu-latest）不需要这层包装。
