--------------------------------------------------------------------------------
-- V101. macro 의 요약을 `#` 주석 줄에서 docstring 으로 옮긴다
--------------------------------------------------------------------------------
-- 지금까지 macro 의 요약은 진입점 `def` 본문의 첫 `#` 주석 줄이었고, orchestration
-- (`MacroSummary.kt`)과 agent-server(`for_step.py`)가 각자 줄 단위로 `source` 를 읽어 그 줄을
-- 찾았다. agent-server 의 parser 가 이제 진입점 `def` 의 첫 statement 로 `"""..."""` 문자열 하나를
-- 받으므로(ARTEL-984), 요약은 docstring 하나로 정한다(ARTEL-985).
--
-- 담는 것 셋:
--   1. macro.docstring                    — 진입점 `def` 의 docstring. `MACRO_REGISTER` 가 채운다
--   2. macro_source_before_docstring      — 이 migration 이 바꾼 행의 원래 `source` 와 tree
--   3. move_macro_summary_comment_into_docstring(source, entry_name) — 한 행의 `source` 를 바꾸는 함수
--
-- ## 배포 순서
--
-- agent-server 는 읽을 때마다 `source` 를 다시 파싱한다. docstring 을 받는 parser(ARTEL-984)가 먼저
-- 배포되어 있지 않으면 이 migration 이 바꾼 macro 는 전부 읽을 때 거절된다.
--
-- ## `updated_at` 을 건드리지 않는다
--
-- 이 rewrite 는 agent 나 사람이 정의를 고친 것이 아니다. `updated_at` 이 움직이면 목록 화면과 macro
-- 이력이 이 migration 을 수정으로 읽는다. `macro` 에는 `updated_at` 을 바꾸는 trigger 가 없고
-- (V98 이 만들지 않았고 이후 migration 도 없다), 아래 `UPDATE` 는 그 칸을 적지 않는다.

--------------------------------------------------------------------------------
-- 1. macro.docstring
--------------------------------------------------------------------------------
-- 진입점 `def` 의 docstring 글자. agent-server 가 `inspect.cleandoc` 으로 정리한 값이고, 따옴표와
-- escape 는 풀린 상태다. docstring 이 없으면 NULL 이다.
--
-- `source` 안에도 같은 글자가 있으므로 일부러 둔 사본이다. orchestration 은 Python 을 파싱하지 않고,
-- scene context 의 `macros[].summary` 와 macro 검색(ARTEL-986)이 이 칸을 읽는다.
ALTER TABLE macro
    ADD COLUMN IF NOT EXISTS docstring TEXT NULL;

--------------------------------------------------------------------------------
-- 2. macro_source_before_docstring — 되돌릴 때 쓸 원본
--------------------------------------------------------------------------------
-- 이 migration 이 `source` 를 바꾼 행만 담는다. 바꾸지 않은 행은 원본이 그대로 `macro` 에 있다.
--
-- 되돌리는 법: `UPDATE macro m SET source = b.source, definition_json = b.definition_json,
-- docstring = NULL FROM macro_source_before_docstring b WHERE b.macro_id = m.id`.
--
-- stage 와 prod 에서 바뀐 `source` 를 확인한 뒤 이후 migration 이 이 표와 아래 함수를 함께 지운다.
CREATE TABLE IF NOT EXISTS macro_source_before_docstring (
    macro_id BIGINT PRIMARY KEY REFERENCES macro (id) ON DELETE CASCADE,
    source TEXT NOT NULL,
    definition_json JSONB NOT NULL,
    copied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--------------------------------------------------------------------------------
-- 3. move_macro_summary_comment_into_docstring — 한 행의 rewrite
--------------------------------------------------------------------------------
-- `source` 와 진입점 이름을 받아 바뀐 `source` 와 docstring 글자를 돌려준다. 바꿀 것이 없으면 둘 다
-- NULL 이다.
--
-- 줄을 고르는 규칙은 `MacroSummary.firstBodyComment`(ARTEL-934, 이 변경에서 지운다)와 같다.
--   1. 진입점 `def` 줄: `^def\s+<entry_name>\s*\(` 에 맞는 첫 줄. helper `def` 가 앞에 있어도 이름으로
--      찾는다
--   2. signature 의 끝: 그 줄부터 내려가며 오른쪽 공백을 뗀 글자가 `:` 로 끝나는 첫 줄. 여러 줄
--      signature 의 닫는 `)` 줄이 여기 걸린다
--   3. 본문: signature 다음 줄부터, 빈 줄 · 들여쓴 줄 · `)` 로 시작하는 줄이 이어지는 동안. 들여쓰지
--      않은 다음 줄(진입점 뒤의 helper `def` 등)에서 끝난다
--   4. 요약 줄: 본문에서 앞뒤 공백을 뗀 글자가 `#` 로 시작하고 `#` 뒤 공백을 뗀 글자가 비지 않은 첫
--      줄. 다른 statement 뒤에 있어도 고른다 — 지금 scene context 가 보여 주는 요약을 그대로
--      남기려는 사용자 결정이다(2026-10-13). `# ` 만 있는 빈 주석 줄은 건너뛴다
--
-- 바꾸는 법: 요약 줄을 지우고 signature 바로 다음에 `<indent>"""<text>"""` 한 줄을 넣는다.
--   - `<indent>` 는 본문의 첫 statement 줄(빈 줄도 주석 줄도 아닌 줄)의 들여쓰기다. docstring 은
--     statement 라 그 줄과 들여쓰기가 같아야 Python 이 받는다. statement 가 없으면 지운 주석 줄의
--     들여쓰기, 그것도 없으면 공백 4칸이다
--   - `\` 를 `\\` 로, `"` 를 `\"` 로 바꿔 넣는다. 주석에 `"""` 가 있거나 글자가 `"` 나 `\` 로 끝나도
--     결과가 늘 Python 문자열로 파싱된다. docstring 칸에는 escape 하기 전 글자를 돌려준다
--   - 줄바꿈은 원본을 따른다. `\n` 으로 나누고 각 줄 끝의 `\r` 을 줄의 일부로 둔 채 다시 `\n` 으로
--     잇는다. 그래서 CRLF 원본은 CRLF 로, 끝 줄바꿈이 있던 원본은 그대로 남는다. 넣는 줄은 signature
--     줄이 `\r` 로 끝나면 `\r` 을 붙인다
--
-- 바꾸지 않는 경우: 진입점 `def` 가 없다 · signature 끝을 못 찾는다 · 요약 줄이 없다 · 본문의 첫
-- statement 가 이미 문자열이다. 마지막 경우는 이미 docstring 이 있는 것이라 둘째 문자열을 넣으면
-- parser 가 거절한다.
--
-- ## 함수를 남기는 이유
--
-- 테스트(`MacroDocstringMigrationTest`)가 이 함수를 직접 불러 경계 경우의 결과 `source` 를 글자
-- 그대로 비교하고, stage 데이터 사본으로 dry run 할 때도 같은 함수를 쓴다. 입력만 보고 결과를
-- 내는 함수라(`IMMUTABLE`) 남아 있어도 다른 데이터에 영향이 없다. 위 백업 표를 지우는 migration 이
-- 이 함수도 지운다.
CREATE OR REPLACE FUNCTION move_macro_summary_comment_into_docstring(
    macro_source TEXT,
    entry_name TEXT,
    OUT rewritten_source TEXT,
    OUT docstring TEXT
)
    LANGUAGE plpgsql
    IMMUTABLE
AS
$$
DECLARE
    source_lines TEXT[] := string_to_array(macro_source, E'\n');
    line_count INT := coalesce(array_length(source_lines, 1), 0);
    -- 진입점 이름은 Python 식별자지만 정규식에 넣기 전에 영숫자와 `_` 가 아닌 글자를 escape 한다.
    definition_pattern TEXT := '^def\s+' || regexp_replace(entry_name, '([^A-Za-z0-9_])', '\\\1', 'g') || '\s*\(';
    whitespace TEXT := E' \t\f\v';
    definition_index INT;
    signature_end INT;
    body_end INT;
    comment_index INT;
    comment_text TEXT;
    statement_indent TEXT;
    comment_indent TEXT;
    line_text TEXT;
    trimmed TEXT;
    line_break TEXT;
    docstring_line TEXT;
    line_index INT;
BEGIN
    IF macro_source IS NULL OR entry_name IS NULL THEN
        RETURN;
    END IF;

    -- 1. 진입점 `def` 줄
    FOR line_index IN 1..line_count LOOP
        IF rtrim(source_lines[line_index], E'\r') ~ definition_pattern THEN
            definition_index := line_index;
            EXIT;
        END IF;
    END LOOP;
    IF definition_index IS NULL THEN
        RETURN;
    END IF;

    -- 2. signature 의 끝
    FOR line_index IN definition_index..line_count LOOP
        IF rtrim(source_lines[line_index], whitespace || E'\r') LIKE '%:' THEN
            signature_end := line_index;
            EXIT;
        END IF;
    END LOOP;
    IF signature_end IS NULL THEN
        RETURN;
    END IF;

    -- 3. 본문의 끝
    body_end := signature_end;
    FOR line_index IN signature_end + 1..line_count LOOP
        line_text := rtrim(source_lines[line_index], E'\r');
        EXIT WHEN NOT (line_text ~ '^\s*$' OR line_text ~ '^\s' OR left(line_text, 1) = ')');
        body_end := line_index;
    END LOOP;

    -- 4. 요약 줄과 첫 statement 줄
    FOR line_index IN signature_end + 1..body_end LOOP
        line_text := rtrim(source_lines[line_index], E'\r');
        trimmed := btrim(line_text, whitespace);
        CONTINUE WHEN trimmed = '';
        IF left(trimmed, 1) = '#' THEN
            IF comment_index IS NULL AND btrim(substr(trimmed, 2), whitespace) <> '' THEN
                comment_index := line_index;
                comment_text := btrim(substr(trimmed, 2), whitespace);
                comment_indent := substring(line_text FROM '^[ \t]*');
            END IF;
            CONTINUE;
        END IF;
        IF statement_indent IS NULL THEN
            -- 첫 statement 가 문자열이면 이미 docstring 이 있다.
            IF trimmed ~ '^[rRbBuUfF]{0,2}[''"]' THEN
                RETURN;
            END IF;
            statement_indent := substring(line_text FROM '^[ \t]*');
        END IF;
    END LOOP;
    IF comment_index IS NULL THEN
        RETURN;
    END IF;

    line_break := CASE WHEN right(source_lines[signature_end], 1) = E'\r' THEN E'\r' ELSE '' END;
    docstring_line := coalesce(nullif(statement_indent, ''), nullif(comment_indent, ''), '    ')
        || '"""' || replace(replace(comment_text, '\', '\\'), '"', '\"') || '"""' || line_break;

    rewritten_source := array_to_string(
        source_lines[1:signature_end]
            || ARRAY[docstring_line]
            || source_lines[signature_end + 1:comment_index - 1]
            || source_lines[comment_index + 1:line_count],
        E'\n'
    );
    docstring := comment_text;
END;
$$;

--------------------------------------------------------------------------------
-- 4. 기존 행 rewrite
--------------------------------------------------------------------------------
-- 원본을 먼저 백업 표에 적고, 그 다음 `macro` 를 바꾼다. 두 문장이 한 migration transaction 안이라
-- 백업 없이 바뀐 행은 생기지 않는다. 같은 함수를 두 번 부르지만 입력이 같으면 결과가 같은 함수라 두
-- 문장이 고르는 행이 같다.
INSERT INTO macro_source_before_docstring (macro_id, source, definition_json)
SELECT m.id, m.source, m.definition_json
FROM macro m
         CROSS JOIN LATERAL move_macro_summary_comment_into_docstring(m.source, m.name) AS rewrite
WHERE rewrite.rewritten_source IS NOT NULL
ON CONFLICT (macro_id) DO NOTHING;

-- `definition_json` 은 두 칸을 맞춘다.
--   - `entry.docstring`: agent-server 의 `MacroFunction.docstring` 자리. 새로 등록한 tree 와 같은 모양이
--     된다. `entry` 가 없는 tree 에는 `jsonb_set` 이 아무것도 더하지 않는다
--   - `source`: tree 가 원문 사본을 들고 있을 때만 바뀐 원문으로 맞춘다. 두 원문이 갈리면 어느
--     쪽이 실행되는 정의인지 읽는 사람이 알 수 없다
UPDATE macro m
SET source          = rewrite.rewritten_source,
    docstring       = rewrite.docstring,
    definition_json = CASE
                          WHEN jsonb_typeof(m.definition_json -> 'source') = 'string'
                              THEN jsonb_set(
                                  jsonb_set(m.definition_json, '{entry,docstring}', to_jsonb(rewrite.docstring)),
                                  '{source}', to_jsonb(rewrite.rewritten_source)
                              )
                          ELSE jsonb_set(m.definition_json, '{entry,docstring}', to_jsonb(rewrite.docstring))
                      END
FROM (
         SELECT target.id, result.rewritten_source, result.docstring
         FROM macro target
                  CROSS JOIN LATERAL move_macro_summary_comment_into_docstring(target.source, target.name) AS result
         WHERE result.rewritten_source IS NOT NULL
     ) AS rewrite
WHERE m.id = rewrite.id;
