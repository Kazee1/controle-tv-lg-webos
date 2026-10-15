# Testes

## Automatizados

Execute:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Os testes locais cobrem reconhecimento de respostas SSDP, rejeição de dispositivos alheios, gramática do pointer socket, campos essenciais do manifesto e validação de MAC.

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
