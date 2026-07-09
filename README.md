# Structured Concurrency Demo for Java 26

Демонстрационный проект показывает возможности preview API `java.util.concurrent.StructuredTaskScope`:

- параллельный запуск связанных подзадач в виртуальных потоках;
- гарантированное ожидание дочерних задач через `try-with-resources`;
- отмена sibling-задач при ошибке;
- гонка за первым успешным результатом;
- timeout на весь scope;
- кастомная политика завершения через `StructuredTaskScope.Joiner`.

Дополнительно в проекте есть реализация тех же сценариев без Structured Concurrency:

- `ExecutorServiceDemo` показывает старый подход через `ExecutorService`, `Future`, `ExecutorCompletionService`, ручную отмену и `shutdownNow`;
- `StructuredConcurrencyDemo` показывает тот же набор задач через `StructuredTaskScope`.

## Известные входные данные

- В JDK 26 `StructuredTaskScope` остается preview API, поэтому нужны `--enable-preview` при компиляции и запуске.
- Проект ориентирован на API JDK 26: `StructuredTaskScope.open(...)`, `Joiner.allSuccessfulOrThrow()`, `Joiner.anySuccessfulOrThrow()`.
- JDK 21 и JDK 25 имеют другие preview-сигнатуры этого API, поэтому этот исходный код под них без адаптации не собирается.

## Предположения

- Основная демонстрационная версия - JDK 26.
- Внешние зависимости не нужны.

## Как запустить

Из корня проекта:

```cmd
.\run-all.cmd
```

Сравнить старый и новый подход:

```cmd
.\run-compare.cmd
```

То же самое из POSIX shell, Git Bash или WSL:

```sh
./run-compare.sh
```

То же самое через PowerShell напрямую:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-all.ps1
```

Отдельный сценарий:

```cmd
.\run.cmd all-success
.\run.cmd fail-fast
.\run.cmd race
.\run.cmd timeout
.\run.cmd quorum
```

Отдельный сценарий в старой реализации:

```cmd
.\run-legacy.cmd all-success
.\run-legacy.cmd fail-fast
.\run-legacy.cmd race
.\run-legacy.cmd timeout
.\run-legacy.cmd quorum
```

Сравнение одного сценария:

```cmd
.\run-compare.cmd fail-fast
```

Shell-варианты тех же команд:

```sh
./run.sh all-success
./run-legacy.sh all-success
./run-compare.sh fail-fast
./compile.sh
```

Если JDK 26 не найден автоматически:

```sh
JDK_HOME="/mnt/c/Program Files/Axiom/AxiomJDK-26" ./run-compare.sh
```

Компиляция без запуска:

```cmd
.\compile.cmd
```

Переопределение JDK:

```powershell
.\scripts\run-all.ps1 -JdkHome "C:\Program Files\Axiom\AxiomJDK-26"
```

## Сценарии

| Сценарий      | Что показывает                                                                                         |
|---------------|--------------------------------------------------------------------------------------------------------|
| `all-success` | Несколько независимых операций выполняются параллельно, а `join()` возвращает все успешные результаты. |
| `fail-fast`   | Ошибка одной подзадачи отменяет весь scope; медленная sibling-задача получает interrupt.               |
| `race`        | `anySuccessfulOrThrow()` возвращает первый успешный результат и отменяет более медленные задачи.       |
| `timeout`     | `withTimeout(...)` задает дедлайн для scope, после чего незавершенные подзадачи отменяются.            |
| `quorum`      | Кастомный `Joiner` отменяет scope после двух успешных ответов из четырех реплик.                       |

## Сравнение подходов

| Задача | До Structured Concurrency | С Structured Concurrency |
| --- | --- | --- |
| Запустить связанные подзадачи | Создать `ExecutorService`, отправить `Callable` через `submit` или `ExecutorCompletionService`. | Открыть `StructuredTaskScope` и вызвать `fork`. |
| Дождаться всех успешных результатов | Хранить список `Future`, вызывать `get`, отдельно отменять остальные при ошибке. | Использовать `Joiner.allSuccessfulOrThrow()` или `awaitAllSuccessfulOrThrow()`. |
| Вернуть первый успешный результат | Читать завершения через `ExecutorCompletionService`, считать ошибки, отменять остальные `Future`. | Использовать `Joiner.anySuccessfulOrThrow()`. |
| Задать общий timeout | Вызвать `invokeAll(..., timeout, unit)` или самому считать дедлайн и отменять задачи. | Указать `config.withTimeout(...)` на scope. |
| Реализовать quorum | Вручную считать успехи/ошибки и не забыть отменить оставшиеся задачи. | Написать `Joiner`, где политика завершения живет рядом с областью задач. |
| Завершить ресурсы | Всегда нужен `shutdown`/`shutdownNow` и ожидание `awaitTermination`. | `try-with-resources` закрывает scope и не дает дочерним задачам пережить родительскую операцию. |

## Практические рекомендации

- Используйте structured concurrency, когда подзадачи имеют общий жизненный цикл и результат родительской операции не должен переживать дочерние потоки.
- Для простых политик сначала берите встроенные `Joiner`: `allSuccessfulOrThrow`, `awaitAllSuccessfulOrThrow`, `anySuccessfulOrThrow`.
- Кастомный `Joiner` нужен, когда политика завершения не сводится к "все успешны" или "первый успешный".
- Код внутри `Joiner.onComplete(...)` должен быть thread-safe: несколько подзадач могут завершиться одновременно.
