# Validação do SnapLivre 1.0.1

## Teste manual informado

Em **13/10/2026**, o responsável confirmou que testou o aplicativo e que está funcionando. Aparelho, versão do Android e cenários não foram detalhados. Trata-se de relato de uso manual da versão disponível.

## Build original

- APKs release ARM64, ARMv7, x86, x86_64 e universal compilados.
- Seis testes instrumentados compilados.
- Android Lint: zero erros, com avisos não bloqueantes.
- Manifesto: versão 1.0.1, código 2, mínimo API 26 e alvo API 35.
- Player interno, janela flutuante e dependências AndroidX Media3 removidos.
- Busca nas DEX ARM64 sem os namespaces selecionados de player, Media3, publicidade ou aplicativo de referência; a busca não equivale a análise universal de todos os SDKs.
- Hash do extrator inicial incorporado conferido.

O [registro original de build](../dist/VALIDATION.md) e [as dependências resolvidas](../dist/dependencies.txt) acompanham o código.

## Conferência para publicação

Em **13/10/2026**, ARM64, ARMv7 e universal foram verificados novamente com `apksigner`: assinaturas válidas pelo esquema v2. Os hashes conferem com o pacote do servidor. Os APKs publicados mantêm os mesmos bytes da entrega original.

| APK | SHA-256 |
| --- | --- |
| ARM64 | `086987ce51f931c9e793c213b09391bc4f855fd1487d2bb6a8c3f1c81aec8996` |
| ARMv7 | `1221bd287bd805a3dcfc735f993acc288ed88ca457ad52a5ffb8f08f76af4ac5` |
| Universal | `54f32284ece8f7f0553abce38d9754da6dadb93e29db94129fef6d548977b1e8` |

## Execução automatizada pendente

A execução instrumentada no Android continua pendente. A tentativa original usou emulador Android 11 sem KVM; os serviços de sistema reiniciaram e a instalação não concluiu em um ambiente utilizável. Isso não foi contabilizado como aprovação ou falha do app.

```bash
./gradlew :app:connectedDebugAndroidTest
```

A suíte usa vídeo sintético e servidor HTTP local no aparelho. Cobre MP4, MP3, codecs, MediaStore, pausa/retomada HTTP Range, cancelamento, fila, navegação e compartilhamento. Veja [tests/README.md](../tests/README.md).

## Sites públicos

O registro original contém busca YouTube bem-sucedida no computador e inspeção com exigência anti-bot. Esses registros de host não comprovam, sozinhos, downloads no Android. A compatibilidade depende dos serviços e da cobertura do yt-dlp.
