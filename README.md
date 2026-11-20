# Lumen Terminal 2.0

Terminal Android com identidade visual própria.

## Funcionalidades
- Interface escura premium com identidade verde-esmeralda e ícone original
- Console do shell Android (`/system/bin/sh`)
- Execução de comandos em segundo plano com timeout de 60 segundos
- Histórico de comandos, navegação de pastas, copiar/colar saída
- Teclado auxiliar, fonte ajustável e preferências persistentes
- Compilação automática do APK de depuração via GitHub Actions

## Limitações técnicas
**Este projeto ainda não é uma distribuição do Termux.** Não inclui emulador PTY interativo, bootstrap Linux, `pkg`, `apt`, pacotes Python/Node.js ou sessões persistentes de shell. Os comandos externos são executados pelo shell do Android, em processos separados.

Para alcançar compatibilidade integral com o Termux é preciso integrar seu motor de terminal e construir um bootstrap próprio com prefixo compatível. A distribuição do Termux usa GPLv3 e a reutilização de componentes exige cumprimento das licenças e atribuições.

## Compilar
Abra **Actions → build-apk** e baixe o artifact `lumen-debug-apk` após uma execução bem-sucedida.

O APK de debug é para testes. Para distribuição pública é necessário assinar uma versão release com chave privada própria.
