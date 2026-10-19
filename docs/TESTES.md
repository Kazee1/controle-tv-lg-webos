# Testes

## Automatizados

Execute:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Os testes locais cobrem reconhecimento de respostas SSDP, rejeição de dispositivos alheios, gramática do pointer socket, campos essenciais do manifesto e validação de MAC.

Também cobrem identidade SSDP estável após mudança de IP, recusa de identificação apenas pelo nome, estados de energia em transição, invalidação de socket após erro/timeout/envio recusado e proteção contra callbacks de uma sessão antiga derrubarem a nova.

## Correção de conexão — 19/10/2026

- 15 testes unitários aprovados e APK debug compilado.
- Atualização instalada no celular preservando os dados e o pareamento.
- Celular conectado ao endereço atual da TV, sem manter o endereço antigo do erro.
- Volume +/− e mudo/desmutar acionados pela interface do celular e confirmados por leitura independente da TV; valores originais restaurados.
- Direita/esquerda acionadas pelo anel do celular e confirmadas por capturas da TV, alternando o foco entre TV Aberta e YouTube.
- Desligar pelo celular mostrou standby; Wake-on-LAN seguido de reconexão mostrou ligada no endereço atual.
- Eventos reais durante desligamento incluíram `Active` com `processing=Request Power Off`, seguido de `Suspend`. A transição não é mais interpretada como confirmação de TV ligada.
- Sem rede não permite distinguir com certeza TV desligada de roteador/Wi-Fi indisponível; o texto informa essa incerteza.
- A recuperação automática de IP exige identidade estável (UDN/MAC), pareamento válido e certificado compatível. Certificado LG isolado não identifica uma TV única. Se não houver prova suficiente, selecionar em Configurações → Procurar TVs / IP.
- Não foi provocado desligamento do Wi-Fi do celular nesta sessão de ADB sem fio. Cobertura de falhas de socket em testes automatizados não substitui esse teste físico.

## Roteiro na TV real

1. Testar descoberta e IP manual separadamente.
2. Parear, fechar/reabrir o app e confirmar que não há novo prompt.
3. Executar todas as direções, toque longo, OK, Home e Voltar.
4. Alterar volume pelo app e pelo controle físico; observar a atualização.
5. Abrir um app anunciado e trocar para cada entrada listada.
6. Testar movimento, clique e rolagem; calibrar sensibilidade.
7. Focar um campo webOS compatível e testar texto, Enter e Backspace; repetir sem foco.
8. Registrar MAC/interface, desligar e testar WOL com Mobile TV On habilitado.
9. Ler imagem e tentar uma única alteração reversível; confirmar nova leitura.
10. Deixar a TV ligada com o app ativo, interromper o acompanhamento e conferir se o painel descreve a lacuna.
11. Desligar Wi‑Fi durante um comando e verificar responsividade/reconexão.

Não use o resultado do fake/simulador como prova de compatibilidade do firmware real.
