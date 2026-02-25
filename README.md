# Multi-Version Minecraft Java Launcher (Sample Architecture)

This repository contains a **legal launcher architecture sample** that integrates with Minecraft files users already own.
It does **not** redistribute proprietary Minecraft code.

## Example usage

### 1) Launch a version (vanilla)

```bash
java -cp out com.example.multimclauncher.Launcher --version 1.20.6 --loader none --username Steve
```

### 2) Launch with Fabric (when installed for that version)

```bash
java -cp out com.example.multimclauncher.Launcher --version 1.20.1 --loader fabric --username Alex
```

### 3) Launch and probe multiplayer server compatibility

```bash
java -cp out com.example.multimclauncher.Launcher --version 1.16.5 --server play.example.net:25565 --loader forge
```

## Compile

```bash
mkdir -p out
javac -d out src/main/java/com/example/multimclauncher/*.java
```
