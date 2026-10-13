> Registro do build original. O teste manual informado e a conferência desta publicação estão em [docs/VALIDACAO.md](../docs/VALIDACAO.md). Materiais de análise citados neste registro permanecem no servidor de trabalho.

# Validação do SnapLivre 1.0.1

Versão dedicada a busca, links, formatos e downloads. Player interno, botões de reprodução, janela flutuante e dependências AndroidX Media3 removidos. Abrir um arquivo concluído usa `ACTION_VIEW` e o aplicativo de arquivos/mídia do celular, com permissão de leitura da URI.

## Verificações concluídas

- Compilação release para ARM64, ARMv7, x86, x86_64 e universal: passou.
- Compilação dos seis testes instrumentados de downloads e interface: passou.
- Android Lint: zero erros; avisos não bloqueantes registrados em `lint-report.html`.
- Assinaturas dos APKs ARM64, ARMv7 e universal entregues: verificadas pelo `apksigner`.
- Manifesto: versão 1.0.1, código 2, mínimo API 26, alvo API 35; sem declaração de player ou PiP.
- Todas as DEX do APK ARM64: ausência de `PlayerActivity`, namespaces Media3, namespaces do aplicativo original e dos SDKs de anúncios selecionados. Confira o limite da busca em `ad-sdk-check.json`.
- Recurso inicial yt-dlp no APK: SHA-256 confere com a release fixada no código.
- APK original: SHA-256 preservado, sem execução ou modificação.

O build usa assinatura de desenvolvimento local, conforme o README. Código e dependências foram conferidos, mas esta validação não equivale a testes de todos os sites ou aparelhos.

## Execução Android pendente

Os testes instrumentados não chegaram a executar. Neste ambiente sem aceleração KVM, o emulador Android 11 reiniciou serviços de sistema e não concluiu uma instalação utilizável. O gerenciador de pacotes respondeu `Cannot access system provider: 'settings' before system providers are installed!` durante a tentativa de instalação. O emulador foi encerrado; esses resultados não foram contabilizados como testes aprovados ou como falha do app.

Os seis testes estão em `app/src/androidTest`. Podem ser executados em um aparelho/emulador funcional com `./gradlew :app:connectedDebugAndroidTest`. Eles verificam MP4, conversão MP3, codecs, exportação MediaStore, pausa/retomada HTTP Range, cancelamento, fila, navegação e parsing de links.

## Sites públicos

A busca YouTube retornou dois resultados no teste realizado no computador com o extrator fixado. A inspeção de um vídeo recebeu exigência anti-bot do YouTube. Isso não comprova funcionamento de download YouTube no Android nem garante disponibilidade de todas as fontes. Registros estão em `analysis/live-search-host.json` e `analysis/live-inspect-host.log`.

O relatório REA do APK original está em `analysis/REPORT.md`. Ele separa observações estáticas, inferências e pontos que não foram verificados.
