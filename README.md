# Amarelo Watch

Transmita a tela do celular para qualquer smart TV na mesma rede Wi-Fi.

O app abre um servidor HTTP local e mostra um QR code. Você aponta a câmera
da TV, abre a página no navegador e a tela aparece em tempo real.

## Como funciona

```
Celular (Android 8+)                    Smart TV
┌────────────────────┐                 ┌──────────────────────┐
│ MediaProjection     │   Wi-Fi local   │ Navegador da TV      │
│      ↓              │  ─────────────> │  <img> com MJPEG     │
│ VirtualDisplay      │   HTTP :8080    │                      │
│      ↓              │                 └──────────────────────┘
│ JPEG por software   │
└────────────────────┘
```

- **Captura:** `MediaProjection` → `VirtualDisplay` → `ImageReader` em `RGBA_8888`.
- **Transporte:** servidor HTTP próprio (sem dependências Android) servindo
  `multipart/x-mixed-replace`, que qualquer navegador exibe nativamente.
- **Vídeo apenas, sem áudio.**
- Escolha de protocolo pensando em **compatibilidade**: MJPEG abre em
  qualquer TV, mesmo com navegador antigo ou bloqueado. H.264/WebRTC seria
  mais eficiente, mas não é aceito em toda parte.

## Compilando

Requer JDK 17 e Android SDK 35.

```bash
./gradlew :app:assembleRelease
```

O APK sai em `app/build/outputs/apk/release/app-release.apk`.

### Assinatura

Credenciais ficam em `app/keystore.properties` (não versionado):

```properties
storeFile=keystore/amarelo.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Sem esse arquivo o build release sai sem assinatura — o app compila e roda
normalmente em debug, mas não vai para a Play Store.

## Testes

```bash
./gradlew :app:testReleaseUnitTest
```

Cobre o protocolo HTTP: HTML do player, JSON do heartbeat, multipart MJPEG,
`snapshot.jpg`, fallback de porta, encerramento limpo e ausência de lixo no
buffer.

## Limitações conhecidas

- **Girar o celular durante a transmissão:** o Android não permite
  redimensionar uma `MediaProjection` já criada, então a stream mantém a
  orientação em que foi iniciada e o conteúdo aparece com tarjas pretas. O
  app avisa na tela. Para usar toda a resolução, volte à orientação original
  ou pare e transmita de novo já na orientação desejada.
- Android 8–9 (`minSdk 24`) não tem `VirtualDisplay.resize()`, então não
  há como recuperar resolução após girar.
- Sem áudio, por opção de projeto.
- O desempenho real em smart TV não foi medido em hardware de consumo; a
  validação foi feita em emulador com renderização por software.

## Privacidade

A transmissão acontece apenas na sua rede local, via HTTP sem criptografia.
Nenhum dado sai do dispositivo e não há servidor na nuvem. Use apenas em
redes Wi-Fi confiáveis.

## Licença

Uso livre para fins pessoais.
