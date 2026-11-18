(function () {
  'use strict';

  var phone = document.getElementById('phone');
  if (!phone) return;

  var sheet = document.getElementById('sheet');
  var scrim = document.getElementById('scrim');
  var sheetTitle = document.getElementById('sheetTitle');
  var tabs = document.getElementById('sheetTabs');
  var skillTab = document.getElementById('skillTab');
  var coverImg = document.getElementById('coverImg');
  var toast = document.getElementById('phoneToast');
  var toastTimer = null;

  var modeTitles = {
    character: '法尔伽【角色牌】',
    action: '重光临晨【行动牌】',
    loading: '卡牌详情',
    error: '卡牌详情'
  };

  function showToast(text) {
    if (!toast) return;
    toast.textContent = text;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toast.classList.remove('show');
    }, 1700);
  }

  function activePanels() {
    var mode = phone.getAttribute('data-mode');
    if (mode === 'loading') return ['loading'];
    if (mode === 'error') return ['error'];
    if (mode === 'action') return ['cover', 'action'];
    return ['cover', 'skills'];
  }

  function setActiveTab(tabName) {
    var mode = phone.getAttribute('data-mode');
    var panels = activePanels();
    var target = tabName;
    if (target === 'skills' && mode === 'action') target = 'action';
    if (panels.indexOf(target) === -1) target = panels[0];

    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (tab) {
      var isActive = tab.getAttribute('data-tab') === tabName;
      tab.classList.toggle('active', isActive);
      tab.setAttribute('aria-selected', isActive ? 'true' : 'false');
    });

    Array.prototype.forEach.call(document.querySelectorAll('.panel'), function (panel) {
      panel.classList.toggle('active', panel.getAttribute('data-panel') === target);
    });
  }

  function syncModeButtons(mode) {
    Array.prototype.forEach.call(document.querySelectorAll('.phone-controls [data-mode]'), function (btn) {
      btn.classList.toggle('active', btn.getAttribute('data-mode') === mode);
    });
  }

  function setMode(mode) {
    phone.setAttribute('data-mode', mode);
    syncModeButtons(mode);
    sheetTitle.textContent = modeTitles[mode] || '卡牌详情';

    var showTabs = mode === 'character' || mode === 'action';
    tabs.classList.toggle('hidden', !showTabs);
    skillTab.textContent = mode === 'action' ? '效果' : '技能';

    if (mode === 'character') {
      coverImg.src = './assets/card-farga.png';
      coverImg.alt = '法尔伽卡面';
    } else if (mode === 'action') {
      coverImg.src = './assets/card-action.png';
      coverImg.alt = '重光临晨卡面';
    }

    Array.prototype.forEach.call(document.querySelectorAll('.note-box'), function (note) {
      note.classList.remove('open');
    });
    Array.prototype.forEach.call(document.querySelectorAll('.kw-chip'), function (chip) {
      chip.classList.remove('active');
    });

    if (showTabs) {
      setActiveTab('skills');
    } else {
      setActiveTab('cover');
    }
  }

  function setTheme(theme) {
    phone.setAttribute('data-theme', theme);
    Array.prototype.forEach.call(document.querySelectorAll('[data-theme-btn]'), function (btn) {
      btn.classList.toggle('active', btn.getAttribute('data-theme-btn') === theme);
    });
  }

  function openSheet() {
    sheet.classList.add('open');
    scrim.classList.add('visible');
  }

  function closeSheet() {
    sheet.classList.remove('open');
    scrim.classList.remove('visible');
    showToast('已关闭（设计稿）。点击任意卡牌可重新打开。');
  }

  // 状态切换（只绑定控制栏与重试按钮，避免 phone-shell 自身的 data-mode 被误绑）
  Array.prototype.forEach.call(document.querySelectorAll('.phone-controls [data-mode], .retry[data-mode]'), function (btn) {
    btn.addEventListener('click', function () {
      var mode = btn.getAttribute('data-mode');
      if (!mode) return;
      setMode(mode);
      openSheet();
    });
  });

  // 主题切换
  Array.prototype.forEach.call(document.querySelectorAll('[data-theme-btn]'), function (btn) {
    btn.addEventListener('click', function () {
      setTheme(btn.getAttribute('data-theme-btn'));
    });
  });

  // Tab 切换
  Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (tab) {
    tab.addEventListener('click', function () {
      setActiveTab(tab.getAttribute('data-tab'));
    });
  });

  // 卡面格式切换（设计稿只切换选中态）
  Array.prototype.forEach.call(document.querySelectorAll('[data-format]'), function (btn) {
    btn.addEventListener('click', function () {
      Array.prototype.forEach.call(document.querySelectorAll('[data-format]'), function (other) {
        other.classList.toggle('active', other === btn);
      });
      showToast(btn.getAttribute('data-format') === 'gif'
        ? '动态卡面：真机由 Coil GIF 解码（设计稿用静态图）'
        : '普通卡面：PNG 预览');
    });
  });

  // 关键词芯片：同一技能卡内原地展开 / 收起
  Array.prototype.forEach.call(document.querySelectorAll('.kw-chip'), function (chip) {
    chip.addEventListener('click', function () {
      var id = chip.getAttribute('data-note');
      var card = chip.closest('.skill-card');
      if (!card) return;
      var note = card.querySelector('[data-note-box="' + id + '"]');
      if (!note) return;
      var open = note.classList.toggle('open');
      chip.classList.toggle('active', open);
    });
  });

  // 下载 / 其他示意按钮
  Array.prototype.forEach.call(document.querySelectorAll('[data-toast]'), function (btn) {
    btn.addEventListener('click', function () {
      showToast(btn.getAttribute('data-toast'));
    });
  });

  // 关闭 / 点击遮罩关闭 / 点击卡牌重新打开
  var closeBtn = document.getElementById('closeBtn');
  if (closeBtn) closeBtn.addEventListener('click', closeSheet);
  if (scrim) scrim.addEventListener('click', closeSheet);
  Array.prototype.forEach.call(document.querySelectorAll('.app-card'), function (card) {
    card.style.cursor = 'pointer';
    card.addEventListener('click', function () {
      setMode('character');
      openSheet();
      showToast('已打开卡牌详情（设计稿）');
    });
  });

  // 审批摘要
  var summary = document.getElementById('approvalSummary');
  var groupNames = ['shape', 'keywords', 'scope', 'colors', 'defaultTab'];
  var groupLabels = {
    shape: '入口形态',
    keywords: '关键词',
    scope: '范围',
    colors: '元素配色',
    defaultTab: '默认 Tab'
  };

  function selectedValue(name) {
    var checked = document.querySelector('input[name="' + name + '"]:checked');
    return checked ? checked.value : '';
  }

  function updateSummary() {
    if (!summary) return;
    var lines = ['批准 GIGI 卡牌图鉴技能展示 V1：'];
    groupNames.forEach(function (name) {
      var value = selectedValue(name);
      if (value) lines.push('- ' + groupLabels[name] + '：' + value);
    });
    summary.textContent = lines.join('\n');
  }

  Array.prototype.forEach.call(document.querySelectorAll('input[name]'), function (input) {
    input.addEventListener('change', updateSummary);
  });

  var copyBtn = document.getElementById('copyApproval');
  if (copyBtn) {
    copyBtn.addEventListener('click', function () {
      var text = summary ? summary.textContent : '';
      var done = function () {
        copyBtn.textContent = '已复制';
        setTimeout(function () { copyBtn.textContent = '复制批准摘要'; }, 1600);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () {
          showToast('复制失败，请手动选择摘要文本');
        });
      } else {
        var area = document.createElement('textarea');
        area.value = text;
        document.body.appendChild(area);
        area.select();
        try { document.execCommand('copy'); done(); }
        catch (e) { showToast('复制失败，请手动选择摘要文本'); }
        document.body.removeChild(area);
      }
    });
  }

  var resetBtn = document.getElementById('resetApproval');
  if (resetBtn) {
    resetBtn.addEventListener('click', function () {
      var defaults = {
        shape: '双 Tab 底部详情 Sheet',
        keywords: '芯片内联展开',
        scope: '角色/魔物技能 + 行动牌效果',
        colors: '启用固定元素色（AA）',
        defaultTab: '技能优先'
      };
      groupNames.forEach(function (name) {
        Array.prototype.forEach.call(document.querySelectorAll('input[name="' + name + '"]'), function (input) {
          input.checked = input.value === defaults[name];
        });
      });
      updateSummary();
    });
  }

  // 初始状态
  setTheme('light');
  setMode('character');
  setActiveTab('skills');
  updateSummary();
})();
