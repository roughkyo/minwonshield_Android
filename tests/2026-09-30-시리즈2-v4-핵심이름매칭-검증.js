// 직책이 포함된 파일명과 주소록 표시 이름의 안전한 연결을 검증합니다.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const codeName = '2026-09-30-민원방패-시리즈2-v4-핵심이름매칭.gs';
const source = fs.readFileSync(path.join(__dirname, '..', 'apps-script', codeName), 'utf8');
const context = vm.createContext({
  console,
  Utilities: {
    formatDate: date => new Date(date.getTime() + 9 * 3600000)
      .toISOString().replace('T', ' ').slice(0, 19)
  }
});
vm.runInContext(source, context);

let passed = 0;
function test(name, body) {
  body();
  passed++;
  console.log('PASS ' + name);
}

function contact(id, name, phone, category = '교육관계자') {
  return { id, name, phone, category };
}

test('직책 앞에서 핵심 이름 추출', () => {
  assert.equal(context.roleBasedName_('윤가민 교무부장님'), '윤가민');
  assert.equal(context.roleBasedName_('진로교육과_문남희 장학사님'), '문남희');
  assert.equal(context.roleBasedName_('배종학 1학년 부장님'), '배종학');
});

test('전체 이름이 같으면 기존 정확 일치 우선', () => {
  const index = new Map([['윤가민 교무부장님', [
    contact('people/1', '윤가민 교무부장님', '010-4104-2348')
  ]]]);
  const result = context.lookupContactByName_(index, '윤가민 교무부장님');
  assert.equal(result.status, 'found');
  assert.equal(result.matchType, 'exact');
});

test('직책 앞 핵심 이름이 주소록 표시 이름에 유일하면 연결', () => {
  const index = new Map([['윤가민광양고 중정의 후예', [
    contact('people/1', '윤가민광양고 중정의 후예', '010-4104-2348')
  ]]]);
  const result = context.lookupContactByName_(index, '윤가민 교무부장님');
  assert.equal(result.status, 'found');
  assert.equal(result.matchType, 'role_name');
  assert.equal(result.contact.phone, '010-4104-2348');
});

test('핵심 이름이 같은 주소록 항목이 여러 개면 자동 선택하지 않음', () => {
  const index = new Map([
    ['윤가민 광양고', [contact('people/1', '윤가민 광양고', '010-1111-1111')]],
    ['윤가민 학부모', [contact('people/2', '윤가민 학부모', '010-2222-2222', '학부모')]]
  ]);
  assert.equal(context.lookupContactByName_(index, '윤가민 교무부장님').status, 'ambiguous');
});

test('직책이 없는 서로 다른 이름은 부분 일치하지 않음', () => {
  const index = new Map([['태형이형 친구', [
    contact('people/1', '태형이형 친구', '010-3333-4444', '일반인')
  ]]]);
  assert.equal(context.lookupContactByName_(index, '태형이형').status, 'not_found');
});

test('전화번호 조회 결과가 없으면 not_found 유지', () => {
  assert.equal(context.lookupContact_(new Map(), '010-9999-9999').status, 'not_found');
});

test('상담 기록에 핵심 이름으로 찾은 전화번호와 연락처 기록', () => {
  const nameIndex = new Map([['윤가민광양고 중정의 후예', [
    contact('people/1', '윤가민광양고 중정의 후예', '010-4104-2348')
  ]]]);
  const fakeFile = {
    getId: () => 'file-1',
    getName: () => '통화 윤가민 교무부장님_260930_131839.m4a',
    getDateCreated: () => new Date('2026-09-30T12:07:02Z'),
    getUrl: () => 'https://drive.google.com/file/d/file-1/view'
  };
  const analysis = {
    phone: '', category: '교육관계자', studentNames: [], keywords: ['학교 업무'],
    purpose: '확인', summary: '학교 업무를 확인함.', mood: '중립'
  };
  const meta = context.parseFileName_(fakeFile.getName());
  const record = context.makeRecord_(fakeFile, meta, analysis, '통화 내용', new Map(), nameIndex);
  assert.equal(record['전화번호'], '010-4104-2348');
  assert.equal(record['연락처이름'], '윤가민광양고 중정의 후예');
  assert.equal(record['상대구분'], '교육관계자');
  assert.match(record['확인사항'], /파일명 핵심 이름으로 주소록 연결/);
});

test('전체 Apps Script 구문 검사', () => {
  new vm.Script(source);
});

console.log('총 ' + passed + '개 핵심 이름 매칭 검증 통과');
