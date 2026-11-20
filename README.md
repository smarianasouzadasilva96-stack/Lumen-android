# Lumen-android

Nosso terminal. Um terminal Android com paleta própria, histórico com setas, `cd`, `clear` e `exit`.

## Compilar sem instalar nada (GitHub Actions)

1. Envie os arquivos deste repositório para o GitHub (pelo site: *Add file → Upload files*).
2. Abra a aba **Actions**. O workflow `build-apk` roda sozinho a cada push.
3. Quando terminar, baixe o artefato **lumen-debug-apk** e instale o `app-debug.apk`.

## Compilar localmente

Requisitos: JDK 17, Android SDK (platform 34) e Gradle 8.7.

```bash
gradle assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

## Observações

- Os comandos rodam com `/system/bin/sh` dentro da pasta privada do app. Por causa da sandbox do Android, comandos que mexem em `/`, em outros apps ou em `/sdcard` podem ser negados.
- Comandos interativos que pedem entrada do teclado não funcionam.
- Tempo máximo por comando: 60 segundos.
