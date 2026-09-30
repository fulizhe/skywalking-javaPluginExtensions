/* quiz.js — 检索练习组件（共享）。
 *
 * 用法（HTML）：
 *   <div class="quiz" data-answer="B">
 *     <p class="q">题干</p>
 *     <fieldset>
 *       <label class="opt"><input type="radio" name="q1" value="A">选项一</label>
 *       <label class="opt"><input type="radio" name="q1" value="B">选项二</label>
 *     </fieldset>
 *     <div class="verdict" hidden></div>
 *     <div class="why">为什么：讲解文字</div>
 *     <button class="reset" type="button">重答</button>
 *   </div>
 *   <script src="../assets/quiz.js"></script>
 *
 * data-answer 填正确选项的 value。可多个（逗号分隔）表示多选。
 * 反馈是即时的：点选项立刻判对错 + 亮出讲解。
 * 无 JS 时 .why 默认隐藏，靠 <noscript> 或 details 兜底（见 CSS 的 details.answer）。
 */
(function () {
  'use strict';

  function init(root) {
    if (root.dataset.answered === '1') return;

    var raw = (root.dataset.answer || '').trim();
    if (!raw) return;
    var correct = raw.split(',').map(function (s) { return s.trim().toUpperCase(); });

    var inputs = root.querySelectorAll('input[type="radio"], input[type="checkbox"]');
    var verdict = root.querySelector('.verdict');
    var why = root.querySelector('.why');
    var reset = root.querySelector('.reset');
    var opts = root.querySelectorAll('.opt');
    if (!inputs.length) return;

    // .why 默认折叠，答完才显示
    if (why) why.hidden = true;

    function nameKey(input) { return input.name; }

    function evaluate() {
      var picked = {};
      var answered = false;
      inputs.forEach(function (i) {
        if (i.checked) { picked[i.value.toUpperCase()] = true; answered = true; }
        var label = i.closest('.opt');
        if (label) label.classList.remove('right', 'wrong');
      });
      if (!answered) return;

      var ok = correct.length === Object.keys(picked).length &&
               correct.every(function (c) { return picked[c]; });

      opts.forEach(function (label) {
        var input = label.querySelector('input');
        if (!input) return;
        var v = input.value.toUpperCase();
        if (correct.indexOf(v) !== -1) label.classList.add('right');
        else if (input.checked) label.classList.add('wrong');
      });

      if (verdict) {
        verdict.hidden = false;
        verdict.className = 'verdict ' + (ok ? 'ok' : 'no');
        verdict.innerHTML = ok
          ? '<span class="mark">✓ 对。</span> 再回想一遍理由，确认你是真的记住而不是猜对的。'
          : '<span class="mark">✗ 不对。</span> 正确项已高亮，先自己想 10 秒再读下面讲解。';
      }
      if (why) why.hidden = false;
      root.dataset.answered = '1';
    }

    inputs.forEach(function (i) { i.addEventListener('change', evaluate); });

    if (reset) {
      reset.addEventListener('click', function () {
        inputs.forEach(function (i) { i.checked = false; });
        opts.forEach(function (l) { l.classList.remove('right', 'wrong'); });
        if (verdict) { verdict.hidden = true; verdict.textContent = ''; }
        if (why) why.hidden = true;
        root.dataset.answered = '0';
      });
    }
  }

  function boot() {
    document.querySelectorAll('.quiz').forEach(init);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
