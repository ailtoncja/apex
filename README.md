# Apex

App para **assistir** vídeos e lives de **YouTube**, **Twitch** e **Kick**, tudo num lugar só. Sem Shorts e sem downloads.
Windows primeiro; o código compartilhado já está organizado para o Android entrar depois.

> **Aviso: projeto feito com inteligência artificial.** O código, os testes e a documentação do Apex foram escritos com a ajuda de IA
> ([Claude](https://www.anthropic.com/claude), da Anthropic), sob a direção do autor. Pode ter erros; use por sua conta e risco, como diz a licença GPL-3.0.

## Baixar

Para Windows 10/11 (64 bits). Instale antes o **[VLC](https://www.videolan.org/vlc/) de 64 bits** (é o player; o Java já vem dentro do app).
Na página de [Releases](https://github.com/ailtoncja/apex/releases/latest) escolha:

- **`Apex-1.0.6.msi`** (ou `Apex-1.0.6.exe`): instalador, com atalho no menu Iniciar e na área de trabalho; não pede administrador.
- **`Apex-1.0.6-windows-x64.zip`**: versão portátil, é só extrair e abrir `Apex.exe`.

Depois de instalado, o Apex **se atualiza sozinho**: confere se saiu versão nova ao abrir (e a cada 6 horas), baixa em segundo plano, confere a
assinatura e pergunta quando reiniciar. Dá para desligar em Ajustes › Atualizações do Apex.

O Windows pode mostrar "O Windows protegeu o computador", porque o programa ainda não tem assinatura digital: clique em *Mais informações* e
depois em *Executar assim mesmo*. Para conferir o arquivo baixado, compare o hash com o do `SHA256SUMS.txt` da release.

## O que tem

| | |
|---|---|
| **YouTube** | início com recomendações, **categorias** de jogos e de assuntos (Música, Futebol…) com lives e vídeos, busca com os filtros do YouTube (data, tipo, duração, recursos como 4K e HDR, ordem), canais, comentários, relacionados, curtir/não curtir, inscrições, histórico, assistir depois, playlists locais e da conta, as **playlists de cada canal** (dá para copiá-las para as suas), e os **clipes que você criou** (Biblioteca › Clipes). Nunca mostra Shorts. |
| **Twitch** | lives (por categoria, com filtro de idioma, de público e busca; canais seguidos) com chat ao vivo, mais **VODs** e **clipes** de cada canal (ordenados por mais vistos ou mais recentes) |
| **Kick** | lives (por categoria, com filtro de idioma, de público e busca; canais seguidos) com chat ao vivo, mais **VODs** e **clipes** de cada canal; esconde conteúdo +18 por padrão |
| **Multi** | várias lives ao mesmo tempo, de qualquer plataforma misturada (até 9), como o multitwitch, o multikick e o multiyoutube: uma grade que se arruma sozinha, som em quantas lives você quiser (de início só na primeira), o botão **AO VIVO / Voltar ao vivo** em cada quadro para tirar o atraso, uma em destaque se quiser, e o chat de cada uma numa aba ao lado. Ao digitar no campo, os canais que você segue aparecem como sugestão (os ao vivo primeiro) e Enter pega o primeiro. Entra pelo menu lateral (ou `Ctrl+6`), pelo campo de cima (um link de qualquer plataforma, o nome de um canal da Twitch ou da Kick, ou o @ ou o nome de um canal do YouTube, cuja live no ar é procurada), ou pelo botão direito em qualquer live ("Assistir no Multi"); links `multitwitch.tv/a/b` e `multikick.com/a/b` colados na busca abrem direto nele. A lista fica guardada, e a qualidade de cada live cai conforme a grade enche (720p com duas, 480p até quatro, 360p acima disso) para o processador aguentar. |
| **Links** | cole na busca um link de vídeo, live, VOD ou clipe (inclusive `youtube.com/clip/…`, que o YouTube não lista por canal) e ele abre direto |
| **Comentários** | no YouTube dá para ler os comentários (mais relevantes ou mais recentes) e, com a conta conectada, **escrever o seu** (Ctrl+Enter publica) |
| **Player** | libVLC com qualidade até 4K, velocidade, legendas, capítulos, retomar de onde parou, tela cheia sem borda (como o F11), modo cinema e mini player |

Tem tela de **Início** (escolha o que ver: Inscrições, Recomendados, Em alta e Ao vivo, e em quais plataformas; os filtros se combinam e os seus canais ao vivo mudam junto), **Ao vivo** (liga mais de uma plataforma ao mesmo tempo; as fileiras de canais e categorias têm setas nas pontas para ver o resto), **Inscrições** (feed misturando as três plataformas, filtro por plataforma e busca por canal ou palavra-chave; a pesquisa também mostra o que combina com os canais que você segue, e "Inscrições" combina com as plataformas, como "Inscrições + Twitch"), **Biblioteca** (histórico, assistir
depois, curtidos, clipes, playlists; os atalhos ficam na barra lateral, aberta ou recolhida), **Canal** (com campo para pesquisar dentro do canal e das playlists dele; todos os vídeos do canal, página por página, e as ordens "Mais recentes", "Mais vistos" e "Mais antigos" nos vídeos e nas transmissões do YouTube, e "Mais recentes" e "Mais vistos" nos VODs da Twitch e da Kick), **Busca** e **Ajustes** (com 4 temas: escuro, claro e dois Subaru, azul e dourado ou preto e dourado; ao escolher um Subaru, a silhueta de um Impreza de rali, com a asa grande e as rodas douradas, acelera pela tela e some; para usar o seu próprio desenho, salve um `subaru.png` — branco sobre preto, ou com fundo transparente, o carro olhando para a esquerda — em `%APPDATA%\Apex` e abra o app de novo). A barra lateral separa os canais por plataforma. Nos Ajustes, a Twitch mostra se a conta tem **Turbo** (sem anúncios). Na Twitch, na Kick e no YouTube dá para escrever no chat das lives com a conta conectada (no YouTube, quando o chat da live está aberto para você). Ao entrar numa live, o chat já mostra as mensagens de antes de você chegar: as últimas 50 na Twitch (com a conta conectada; sem ela a Twitch não as entrega), as últimas 25 na Kick e as que o YouTube guarda. O chat das lives pode ser ocultado (botão no cabeçalho do chat) e o botão direito em qualquer vídeo abre um menu de opções. Atalhos de teclado (aperte `?` ou `F1` para ver a lista toda, que também está em Ajustes): `/` ou `Ctrl+K` pesquisam; `Alt+←/→` (ou os botões laterais do mouse) voltam e avançam; `Ctrl+1…6` trocam de tela; `F5`/`Ctrl+R` atualizam; `Ctrl+B` esconde o menu lateral; `F11` tela cheia da janela; com um vídeo tocando: `Espaço/K`, `F`, `T`, `M`, `←/→`, `J/L`, `↑/↓`, `0–9`, `Home/End` (na live, `End` volta ao ao vivo), `<`/`>` (velocidade), `C`, `Shift+N/P`, `Enter` (escrever no chat da live) e `Esc` (sai da tela cheia, do modo cinema, do campo de pesquisa e da página de resultados). O volume vai até 200% e muda na hora em qualquer faixa: o app recebe do VLC o áudio já decodificado e o toca com um buffer curto, aplicando o volume por último (deixado com o VLC, acima de 100% cada mudança levava mais de 1 s para ser ouvida). Em Ajustes, **Volume acima de 100%** desligado limita a 100%. O volume escolhido pelas setas ou pelo controle fica guardado para o próximo vídeo. Nas lives o botão **AO VIVO** do player mostra se o vídeo está em dia; atrasado (pausa, travadas) vira **Voltar ao vivo • N s atrás** e, ao apertar, abre a live de novo no ponto mais novo. Os filtros do Ao vivo, das Inscrições e da Pesquisa ficam numa barra de uma linha só, como a do YouTube, com setinhas nas pontas quando não cabem; no Início a barra de assuntos é a de sempre (a primeira linha da página, que rola junto com o conteúdo) e, na ponta direita dela, o botão **Filtros**, igual ao da pesquisa, abre a janela com o que mostrar (Inscrições, Recomendados, Em alta, Ao vivo) e as plataformas; os filtros ligados aparecem embaixo, cada um com ✕.

## Como funciona por dentro

- **Kotlin + Compose Multiplatform.** `commonMain` guarda interface, dados e lógica; `desktopMain` guarda o que é do Windows.
- **API interna do YouTube (InnerTube)** para busca, canais, feed, relacionados, chat e ações da conta: rápida e traz as datas.
- **Abertura rápida dos vídeos do YouTube:** o app pede o `player` da API do YouTube falando como o app do iPhone, que devolve em ~0,3 s
  os endereços diretos do vídeo e do áudio, sem login e sem o JavaScript do site, e o primeiro quadro aparece em ~1 s. Só que, sem a
  prova de que o pedido vem do aplicativo oficial, o YouTube entrega apenas o **primeiro minuto** de cada arquivo (e só aceita pedidos de
  um trecho com começo e fim). Por isso o yt-dlp parte junto, desde o clique, e o VLC toca por uma ponte local (`RangeProxy`, só no próprio
  computador): ela busca o começo pela via rápida, em pedaços pequenos, e, quando ele acaba ou o yt-dlp responde (uns 4 s), continua o
  mesmo arquivo (mesmo formato e tamanho) pelo endereço completo dele, sem trocar de player nem travar. Se o yt-dlp falhar, o erro
  aparece na hora. A espiada do VLC pelos últimos 16 bytes do arquivo é respondida na hora. Vídeos que retomam de onde pararam (e pulos
  no meio) esperam o yt-dlp, como antes. Legendas e curtidas também vêm dele.
- **yt-dlp + Deno** resolvem as **lives** do YouTube (a lista HLS delas só sai com os desafios do site, ~4 s), os vídeos que a abertura
  rápida não aceita (login, restrição de idade…), o resto de cada vídeo depois do primeiro minuto, os comentários e os detalhes. O app
  baixa os dois sozinho na primeira abertura (GitHub) e atualiza o yt-dlp a cada 7 dias, porque o YouTube muda toda semana.
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
Quem só quer usar o app baixa o pacote `Apex-1.0.6-windows-x64.zip` (traz o Java dentro; veja `Empacotar.cmd`) e precisa apenas do VLC.

```
gradlew.bat :composeApp:run                              # modo desenvolvimento
gradlew.bat :composeApp:packageUberJarForCurrentOS       # gera o app em um único .jar
Apex.cmd                                                 # abre o app gerado
Empacotar.cmd                                            # gera dist\Apex-1.0.6-windows-x64.zip (Java embutido, para distribuir)
Servidor.cmd                                             # sobe o servidor de contas no seu PC (para a sincronização)
```

Testes: `gradlew.bat :server:test :composeApp:desktopTest` (servidor com PostgreSQL de verdade e dois aparelhos simulados).

No Windows 11 com Java 25, se o Gradle ou o app falharem com `Unable to establish loopback connection`, aponte a variável
`TEMP` para uma pasta simples (por exemplo `C:\Users\<você>\jt`). O `Apex.cmd` já faz isso.

Com o Java 25 ou mais novo, o `Apex.cmd` também monta sozinho (uma vez por `.jar`) um **cache de inicialização** em `%USERPROFILE%\.apex\aot`: um "treino" em segundo plano passeia pelas telas numa janela fora da tela (com dados à parte) e o JVM guarda as classes e o que aprendeu. O app abre em ~1,4 s em vez de ~2,4 s e a primeira visita a cada tela trava menos. Para medir a fluidez, abra com `APEX_FRAMES=1`: o app grava `frames.txt` na pasta de dados (quadros por segundo, travadas, GC, CPU e, tocando vídeo, os quadros que chegam do VLC).

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
