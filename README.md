# Apex

App para **assistir** vídeos e lives de **YouTube**, **Twitch** e **Kick**, tudo num lugar só. Sem Shorts e sem downloads.
Windows primeiro; o código compartilhado já está organizado para o Android entrar depois.

## O que tem

| | |
|---|---|
| **YouTube** | início com recomendações, busca com filtros (ordem, data, duração, só ao vivo), canais, comentários, relacionados, curtir/não curtir, inscrições, histórico, assistir depois, playlists locais e da conta. Nunca mostra Shorts. |
| **Twitch** | lives (por categoria, busca, canais seguidos) com chat ao vivo |
| **Kick** | lives (por categoria, busca, canais seguidos) com chat ao vivo; esconde conteúdo +18 por padrão |
| **Player** | libVLC com qualidade até 4K, velocidade, legendas, capítulos, retomar de onde parou, tela cheia, modo cinema e mini player |

Tem tela de **Início**, **Ao vivo**, **Inscrições** (feed misturando as três plataformas), **Biblioteca** (histórico, assistir
depois, curtidos, playlists), **Canal**, **Busca** e **Ajustes**. Atalhos: `Espaço/K`, `F`, `T`, `M`, `←/→`, `J/L`, `↑/↓`, `0–9`, `C`, `Shift+N`, `Esc`.

## Como funciona por dentro

- **Kotlin + Compose Multiplatform.** `commonMain` guarda interface, dados e lógica; `desktopMain` guarda o que é do Windows.
- **API interna do YouTube (InnerTube)** para busca, canais, feed, relacionados, chat e ações da conta: rápida e traz as datas.
- **yt-dlp + Deno** só para resolver o stream, os comentários e os detalhes do YouTube. O app baixa os dois sozinho na
  primeira abertura (GitHub) e atualiza o yt-dlp a cada 7 dias, porque o YouTube muda toda semana.
- **Twitch** pela API GQL pública e HLS direto; **Kick** pela API pública e HLS direto. Os chats usam WebSocket
  (IRC da Twitch e Pusher da Kick).
- **libVLC** (via vlcj) decodifica; cada quadro é desenhado pelo Compose, então os controles ficam por cima do vídeo.
- **Contas das plataformas:** o Apex abre um navegador que você já tem instalado, você entra na conta e ele importa o login
  (sua senha nunca passa pelo Apex). No **Firefox** ele lê o perfil do próprio Firefox, e se você já estiver logado importa na hora.
  No **Chrome, Edge, Brave, Vivaldi e Opera** abre uma janela separada só do Apex e lê o login pelo protocolo de depuração,
  fechando a janela quando termina. Os logins ficam cifrados no disco com a conta do Windows (DPAPI).
- Dados do usuário em `%APPDATA%\Apex` (JSON). Cada arquivo pode ser apagado sem quebrar nada.
- **Conta do Apex e sincronização:** servidor próprio em Kotlin/Ktor com PostgreSQL (módulos `server` e `shared`).
  Veja [docs/CONTAS-E-SINCRONIZACAO.md](docs/CONTAS-E-SINCRONIZACAO.md).

## Rodar

Requisitos: **VLC 3.x de 64 bits** instalado e um **JDK 21+** (o do Android Studio serve).

```
gradlew.bat :composeApp:run                              # modo desenvolvimento
gradlew.bat :composeApp:packageUberJarForCurrentOS       # gera o app em um único .jar
Apex.cmd                                                 # abre o app gerado
Servidor.cmd                                             # sobe o servidor de contas no seu PC (para a sincronização)
```

Testes: `gradlew.bat :server:test :composeApp:desktopTest` (servidor com PostgreSQL de verdade e dois aparelhos simulados).

No Windows 11 com Java 25, se o Gradle ou o app falharem com `Unable to establish loopback connection`, aponte a variável
`TEMP` para uma pasta simples (por exemplo `C:\Users\<você>\jt`). O `Apex.cmd` já faz isso.

## O que ainda não foi testado com conta real

A leitura do login pelo navegador foi testada com cookies de mentira (Firefox) e com o Chrome de verdade em modo invisível,
mas nunca com a sua conta de verdade. Também não foram testados com conta real: curtir/inscrever/playlists/histórico da
conta do YouTube, importar seguidos da Twitch e da Kick, e enviar mensagem no chat da Twitch.
Enviar mensagem no chat da Kick e do YouTube ainda não existe.

## Roteiro

- [x] Windows: player, YouTube, Twitch, Kick, chat, biblioteca, ajustes
- [x] Conta do Apex com servidor próprio e sincronização entre aparelhos
- [ ] Ligar YouTube, Twitch e Kick por OAuth oficial (sincronização automática com as plataformas)
- [ ] Android (mesmo código compartilhado, player Media3 ou libVLC)
- [ ] Instalador `.msi` (precisa de um JDK com `jpackage`)
- [ ] Enviar mensagens no chat da Kick e do YouTube
