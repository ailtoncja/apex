# Apex

App para **assistir** vídeos e lives de **YouTube**, **Twitch** e **Kick**, tudo num lugar só. Sem Shorts e sem downloads.
Windows primeiro; o código compartilhado já está organizado para o Android entrar depois.

> **Aviso: projeto feito com inteligência artificial.** O código, os testes e a documentação do Apex foram escritos com a ajuda de IA
> ([Claude](https://www.anthropic.com/claude), da Anthropic), sob a direção do autor. Pode ter erros; use por sua conta e risco, como diz a licença GPL-3.0.

## Baixar

Para Windows 10/11 (64 bits). Instale antes o **[VLC](https://www.videolan.org/vlc/) de 64 bits** (é o player; o Java já vem dentro do app).
Na página de [Releases](https://github.com/ailtoncja/apex/releases/latest) escolha:

- **`Apex-1.0.0.msi`** (ou `Apex-1.0.0.exe`): instalador, com atalho no menu Iniciar e na área de trabalho; não pede administrador.
- **`Apex-1.0.0-windows-x64.zip`**: versão portátil, é só extrair e abrir `Apex.exe`.

Depois de instalado, o Apex **se atualiza sozinho**: confere se saiu versão nova ao abrir (e a cada 6 horas), baixa em segundo plano, confere a
assinatura e pergunta quando reiniciar. Dá para desligar em Ajustes › Atualizações do Apex.

O Windows pode mostrar "O Windows protegeu o computador", porque o programa ainda não tem assinatura digital: clique em *Mais informações* e
depois em *Executar assim mesmo*. Para conferir o arquivo baixado, compare o hash com o do `SHA256SUMS.txt` da release.

## O que tem

| | |
|---|---|
| **YouTube** | início com recomendações, busca com filtros (ordem, data, duração, só ao vivo), canais, comentários, relacionados, curtir/não curtir, inscrições, histórico, assistir depois, playlists locais e da conta, as **playlists de cada canal** (dá para copiá-las para as suas), e os **clipes que você criou** (Biblioteca › Clipes). Nunca mostra Shorts. |
| **Twitch** | lives (por categoria, busca, canais seguidos) com chat ao vivo, mais **VODs** e **clipes** de cada canal (ordenados por mais vistos ou mais recentes) |
| **Kick** | lives (por categoria, busca, canais seguidos) com chat ao vivo, mais **VODs** e **clipes** de cada canal; esconde conteúdo +18 por padrão |
| **Links** | cole na busca um link de vídeo, live, VOD ou clipe (inclusive `youtube.com/clip/…`, que o YouTube não lista por canal) e ele abre direto |
| **Player** | libVLC com qualidade até 4K, velocidade, legendas, capítulos, retomar de onde parou, tela cheia sem borda (como o F11), modo cinema e mini player |

Tem tela de **Início**, **Ao vivo**, **Inscrições** (feed misturando as três plataformas), **Biblioteca** (histórico, assistir
depois, curtidos, playlists), **Canal**, **Busca** e **Ajustes**. O chat das lives pode ser ocultado (botão no cabeçalho do chat) e o botão direito em qualquer vídeo abre um menu de opções. Atalhos: `Espaço/K`, `F`/`F11`, `T`, `M`, `←/→`, `J/L`, `↑/↓`, `0–9`, `C`, `Shift+N`, `Esc`.

## Como funciona por dentro

- **Kotlin + Compose Multiplatform.** `commonMain` guarda interface, dados e lógica; `desktopMain` guarda o que é do Windows.
- **API interna do YouTube (InnerTube)** para busca, canais, feed, relacionados, chat e ações da conta: rápida e traz as datas.
- **yt-dlp + Deno** só para resolver o stream, os comentários e os detalhes do YouTube. O app baixa os dois sozinho na
  primeira abertura (GitHub) e atualiza o yt-dlp a cada 7 dias, porque o YouTube muda toda semana.
- **Twitch** pela API GQL pública e HLS direto; **Kick** pela API pública e HLS direto. Os chats usam WebSocket
  (IRC da Twitch e Pusher da Kick).
- **libVLC** (via vlcj) decodifica; cada quadro é desenhado pelo Compose, então os controles ficam por cima do vídeo.
- **Contas das plataformas:** o Apex abre um navegador que você já tem instalado, você entra na conta e ele importa o login
  (sua senha nunca passa pelo Apex). No **Firefox** ele lê o perfil do próprio Firefox, e se você já estiver logado importa na hora; como o Firefox aberto troca os cookies do YouTube
  a cada poucos minutos, o Apex relê o perfil a cada 2 minutos para a sessão não cair.
  No **Chrome, Edge, Brave, Vivaldi e Opera** abre uma janela separada só do Apex e lê o login pelo protocolo de depuração,
  fechando a janela quando termina. Os logins ficam cifrados no disco com a conta do Windows (DPAPI).
- Dados do usuário em `%APPDATA%\Apex` (JSON). Cada arquivo pode ser apagado sem quebrar nada.
- **Conta do Apex e sincronização:** servidor próprio em Kotlin/Ktor com PostgreSQL (módulos `server` e `shared`).
  Veja [docs/CONTAS-E-SINCRONIZACAO.md](docs/CONTAS-E-SINCRONIZACAO.md).

## Rodar

Requisitos: **VLC 3.x de 64 bits** instalado (o app avisa e leva ao download se faltar) e um **JDK 21+** (o do Android Studio serve).
Quem só quer usar o app baixa o pacote `Apex-1.0.0-windows-x64.zip` (traz o Java dentro; veja `Empacotar.cmd`) e precisa apenas do VLC.

```
gradlew.bat :composeApp:run                              # modo desenvolvimento
gradlew.bat :composeApp:packageUberJarForCurrentOS       # gera o app em um único .jar
Apex.cmd                                                 # abre o app gerado
Empacotar.cmd                                            # gera dist\Apex-1.0.0-windows-x64.zip (Java embutido, para distribuir)
Servidor.cmd                                             # sobe o servidor de contas no seu PC (para a sincronização)
```

Testes: `gradlew.bat :server:test :composeApp:desktopTest` (servidor com PostgreSQL de verdade e dois aparelhos simulados).

No Windows 11 com Java 25, se o Gradle ou o app falharem com `Unable to establish loopback connection`, aponte a variável
`TEMP` para uma pasta simples (por exemplo `C:\Users\<você>\jt`). O `Apex.cmd` já faz isso.

## Licença e privacidade

- O Apex é software livre sob a **GNU GPL-3.0** ([LICENSE](LICENSE)); as bibliotecas usadas estão em [NOTICE.md](NOTICE.md).
- O servidor publica os **Termos de Uso** (`/terms`) e a **Política de Privacidade** (`/privacy`); o cadastro só vale com o aceite,
  e a pessoa pode **baixar** os dados (Ajustes › Conta do Apex) e **apagar** a conta (no app ou em `/account/delete`).
- Para abrir ao público: [docs/PUBLICAR.md](docs/PUBLICAR.md).

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
- [x] Servidor pronto para o público: termos e privacidade, limites de uso, exportar e apagar dados, limpeza automática
- [x] Pacote para distribuir com o Java embutido (zip; testado fora da pasta do projeto)
- [ ] Instalador `.msi`/`.exe` (precisa do WiX Toolset instalado)
- [ ] Enviar mensagens no chat da Kick e do YouTube
