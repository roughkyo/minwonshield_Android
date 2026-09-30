// 전사 모델, 파일명 전화번호, 상대구분 개선을 Google API 호출 없이 검증합니다.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const cases = [
  {
    label: '시리즈 1',
    file: '2026-09-30-민원방패-시리즈1-v5-전사분류개선.gs',
    hasContacts: false
  },
  {
    label: '시리즈 2',
    file: '2026-09-30-민원방패-시리즈2-v3-전사분류개선.gs',
    hasContacts: true
  }
];

let passed = 0;
function test(name, body) {
  body();
  passed++;
  console.log('PASS ' + name);
}

function load(item) {
  const source = fs.readFileSync(path.join(__dirname, '..', 'apps-script', item.file), 'utf8');
  const context = vm.createContext({
    console,
    Utilities: {
      formatDate: date => new Date(date.getTime() + 9 * 3600000)
        .toISOString().replace('T', ' ').slice(0, 19)
    }
  });
  vm.runInContext(source, context);
  return { source, context };
}

cases.forEach(item => {
  const loaded = load(item);
  const context = loaded.context;

  test(item.label + ': Gemini 3.8 Flash 사용', () => {
    assert.equal(vm.runInContext('GEMINI_MODEL', context), 'gemini-3.8-flash');
  });

  test(item.label + ': 주소록에 없는 8자리 대표번호를 파일명에서 추출', () => {
    const parsed = context.parseFileName_('통화 15445300_260929_223828.m4a');
    assert.equal(parsed.callTime, '2026-09-29 22:38:28');
    assert.equal(parsed.phone, '1544-5300');
    assert.equal(parsed.ambiguousPhone, false);
    if (item.hasContacts) {
      assert.equal(parsed.contactName, '');
    }
  });

  test(item.label + ': 휴대전화와 유선전화 형식을 유지', () => {
    assert.equal(context.parseFileName_('통화 01012345678_260929_223828.m4a').phone, '010-1234-5678');
    assert.equal(context.parseFileName_('통화 0617431205_260929_223828.m4a').phone, '061-743-1205');
  });

  test(item.label + ': 파일명 번호와 전사 번호가 달라도 파일명 번호를 기록', () => {
    const fakeFile = {
      getId: () => 'file-1',
      getName: () => '통화 15445300_260929_223828.m4a',
      getDateCreated: () => new Date('2026-09-29T14:34:45Z'),
      getUrl: () => 'https://drive.google.com/file/d/file-1/view'
    };
    const analysis = {
      phone: '010-9999-9999', category: '확인필요', studentNames: [], keywords: [],
      purpose: '문의', summary: '문의 전화임.', mood: '중립'
    };
    const meta = context.parseFileName_(fakeFile.getName());
    const record = item.hasContacts
      ? context.makeRecord_(fakeFile, meta, analysis, '통화 내용', new Map(), new Map())
      : context.makeRecord_(fakeFile, meta, analysis, '통화 내용');
    assert.equal(record['전화번호'], '1544-5300');
    assert.equal(record['상대구분'], '확인필요');
    assert.match(record['확인사항'], /전화번호 불일치/);
    assert.match(record['확인사항'], /상대구분 확인필요/);
    if (item.hasContacts) {
      assert.equal(record['연락처이름'], '');
    }
  });

  test(item.label + ': 상대 판단은 통화목적과 전사문 전체를 사용', () => {
    let systemPrompt = '';
    context.generate_ = (parts, system) => {
      systemPrompt = system;
      return JSON.stringify({
        phone: '', category: null, studentNames: [], keywords: [],
        purpose: '문의', summary: '문의 전화임.', mood: null
      });
    };
    const result = context.analyze_('화자 1: 문의드리려고 전화했습니다.', 'fake-key');
    assert.match(systemPrompt, /통화목적을 먼저 파악/);
    assert.match(systemPrompt, /전사문 전체/);
    assert.equal(result.category, '확인필요');
    assert.equal(
      vm.runInContext('CATEGORIES.filter(Boolean).join(",")', context),
      '학부모,교육관계자,일반인,학생,기타,확인필요'
    );
  });

  if (item.hasContacts) {
    test(item.label + ': 기존 완료 행도 파일명 번호로 보완', () => {
      const writes = {};
      const history = {
        getRange: (row, column) => ({
          setValues: values => { writes[column] = values; }
        })
      };
      context.SpreadsheetApp = { flush: () => {} };
      context.readRows_ = () => [{
        rowNumber: 2,
        '파일명': '통화 15445300_260929_223828.m4a',
        '전화번호': '',
        '연락처이름': '',
        '상대구분': '확인필요',
        '처리상태': '완료'
      }];
      context.applyContactsToHistory_(history, new Map(), new Map());
      assert.equal(writes[6][0][0], '1544-5300');
      assert.equal(writes[7][0][0], '');
      assert.equal(writes[8][0][0], '확인필요');
    });
  }

  test(item.label + ': 전체 Apps Script 구문 검사', () => {
    new vm.Script(loaded.source);
  });
});

console.log('총 ' + passed + '개 개선 동작 검증 통과');
