# CleanYourTV UI

Um fork do [CleanYourTV](https://github.com/Gathin23/cleanyourtv) que mantém **tudo** do original e acrescenta
uma segunda tela inicial, com o visual inspirado no [Free My TV](https://github.com/simbasjungles/free-my-tv):
sugestões do TMDB, prateleira de vidro, clima, tema que escurece com o pôr do sol e um screensaver com fotos.

O [README](README.md) do CleanYourTV continua valendo para tudo: o debloat, as listas por modelo em `examples/`,
os scripts em `tools/` e o launcher original em `launcher/`. Este arquivo cobre só o que o fork acrescenta.

## O que muda em relação ao original

| | Launcher original (`launcher/`) | CleanYourTV UI (`launcher-ui/`) |
|---|---|---|
| Tela inicial | Dock e grade, sem rede | "Para você" com sugestões do TMDB, prateleira, clima |
| Internet | Nenhuma permissão | TMDB e Open-Meteo |
| Tamanho | ~429 KB | ~2 MB |
| Android mínimo | 8.0 | 9 |
| Ajustes na TV | Poucos, o resto por tema | Tela de ajustes: prateleira, botões, cidade, tema |
| ADB depois de instalar | Pode fechar | Pode fechar |

**Custo em tokens.** O launcher novo tem mais código, rede e telas. Pedir a um assistente que o adapte à sua TV
faz ele ler mais arquivos e gasta mais tokens do que com o launcher original. Se você quer só uma tela limpa e
mínima, use o `launcher/`.

**Explorar** (botão do controle ou o atalho na tela inicial) segue o app Apple TV e o visual da tela inicial: um
banner com o que está em alta e o recomendado para você, fileiras de seis títulos com "Ver mais" (abre a lista
inteira em grade, carregada conforme você desce), fundo com a capa do título focado e um menu à esquerda (seta para a
esquerda na borda abre; direita ou Voltar fecha): Buscar (com buscas recentes), Início, Minha lista (abas: lista,
favoritos, já assisti, notas), Top 10 do dia, Filmes, Séries, Categorias, o que você fixou (segure OK numa categoria
ou busca), uma página por serviço com app na TV e Ajustes (conta TMDB, serviços, itens fixados). Títulos já vistos
têm um ✓ na capa. "Assistir em…" abre o título direto no app quando o Wikidata conhece o ID dele no serviço (Netflix,
Disney+, Apple TV, Max, Prime Video); senão abre o app. As imagens ficam em cache no disco (até 160 MB, uma semana).

**Segurar OK** num app da tela inicial (prateleira ou Todos os Apps) abre um menu a partir do card: Mover, Logo e cor
(URL de um PNG e cor de fundo), Configurações do app (parar, limpar cache) e Sair.

Para abrir o Explorar pelo computador: `adb shell am start -n io.cleanyourtv.ui/.HomeActivity --es open explore`
(ou `--es open watchlist`).

Nada aqui usa o ADB da própria TV. Debloat, troca da tela inicial e o serviço dos botões são feitos pelo
computador, uma vez, com os scripts do CleanYourTV; depois a depuração pode ser desligada.

## Instalar

1. Siga o [Quick start](README.md#quick-start) do CleanYourTV até aplicar a lista de debloat da sua TV.
2. Crie a configuração e coloque sua chave do TMDB (grátis, em themoviedb.org → Settings → API) e sua cidade:
   ```bash
   cp launcher-ui/config.example.properties launcher-ui/config.properties
   ```
   `config.properties` fica fora do git.
3. Compile e instale. Precisa do JDK 17 e do Android SDK (build-tools 34+, platform android-34):
   ```bash
   TV_HOST=192.168.1.50 KEY_SERVICE=1 launcher-ui/build-and-install.sh
   ```
   `KEY_SERVICE=1` liga o serviço dos botões do controle. `SCREENSAVER=0` mantém o screensaver atual.

O script tem as mesmas proteções do original: confere o Android antes de compilar, só desativa um launcher de
fábrica que reconhece, inicia o novo antes de mexer no antigo e desfaz a troca se a Home não pegar. Se a Home atual
for o launcher original do CleanYourTV, ele fica instalado e ativo.

## Voltar

- Para o launcher original do CleanYourTV:
  `TV_HOST=<ip> tools/tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'`
- Para a TV de fábrica: o `tools/restore.sh` do CleanYourTV, como no [README](README.md#restoring-your-tv).

## Manter em dia com o CleanYourTV

Este fork só acrescenta arquivos (`launcher-ui/`, `README.ui.md`, `docs/ui/` e, por modelo,
`examples/<modelo>/remote.json`), então trazer as novidades do original não conflita:

```bash
git fetch upstream && git merge upstream/main
```

## Contato

Matheus Caldeira, [github.com/matheus-caldeira](https://github.com/matheus-caldeira). Dúvidas e problemas nas
[issues deste repositório](https://github.com/matheus-caldeira/cleanyourtv-ui/issues).

Créditos e licenças: [`launcher-ui/NOTICE`](launcher-ui/NOTICE).
