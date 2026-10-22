# Licenças de terceiros

O Apex é distribuído sob a **GNU GPL versão 3** (arquivo [LICENSE](LICENSE)). Ele usa as bibliotecas abaixo, cada uma
com a sua licença. Nada daqui é incluído sem que a licença permita o uso dentro de um programa GPL-3.0.

| Componente | Para quê | Licença |
|---|---|---|
| [vlcj](https://github.com/caprica/vlcj) 4.x | liga o Java ao libVLC (player) | GPL-3.0 (é o motivo de o Apex ser GPL-3.0) |
| [VLC / libVLC](https://www.videolan.org/) 3.0.24 | decodifica e toca o vídeo; **vem junto** (pasta `resources\vlc`: libVLC e parte dos plugins, sem alterações, do zip oficial da VideoLAN) | LGPL-2.1+ (libVLC), plugins LGPL-2.1+ ou GPL-2.0+ (texto em `resources\vlc\COPYING.txt`) |
| Kotlin, kotlinx (coroutines, serialization) | linguagem e bibliotecas | Apache-2.0 |
| Compose Multiplatform, Material | interface | Apache-2.0 |
| Ktor (cliente e servidor) | rede e servidor HTTP | Apache-2.0 |
| Coil 3 | imagens | Apache-2.0 |
| sqlite-jdbc (Xerial) | ler cookies do Firefox | Apache-2.0 |
| JNA | proteger os logins com o Windows (DPAPI) | LGPL-2.1 ou Apache-2.0 |
| Flyway (community) | migrações do banco | Apache-2.0 |
| HikariCP | conexões com o banco | Apache-2.0 |
| PostgreSQL JDBC | driver do banco | BSD-2-Clause |
| Bouncy Castle | Argon2id (senhas, no servidor) e OpenPGP (conferir a assinatura do yt-dlp, no app) | licença MIT do Bouncy Castle |
| Logback / SLF4J | registros (logs) | EPL-1.0 ou LGPL-2.1 / MIT |
| zonky embedded-postgres | banco embutido só para desenvolvimento e testes | Apache-2.0 (binários: licença do PostgreSQL) |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | baixado pelo app na primeira abertura para resolver streams | Unlicense |
| [Deno](https://deno.com/) | baixado pelo app junto do yt-dlp (motor de JavaScript) | MIT |

O código-fonte do VLC 3.0.24 que vai junto está em <https://download.videolan.org/pub/videolan/vlc/3.0.24/> (`vlc-3.0.24.tar.xz`);
os binários são os de `win64/vlc-3.0.24-win64.zip`, o mesmo endereço, sem mudanças (o build confere o SHA-256 publicado pela VideoLAN).
Se esse endereço sair do ar, peça o código pelo repositório do Apex e ele será enviado.

YouTube, Google, Twitch e Kick são marcas dos seus donos. O Apex é um projeto independente, sem ligação com essas empresas.
