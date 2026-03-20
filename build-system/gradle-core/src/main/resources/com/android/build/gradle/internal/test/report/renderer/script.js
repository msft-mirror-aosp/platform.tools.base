/**
 * UI Utilities
 * Collection of helper functions for DOM manipulation and common UI patterns.
 */
const UIUtils = {
  /**
   * Builds a multi-select dropdown with "Select All" / "Clear" actions
   * and a scrollable list of options.
   */
  buildActionDropdown(container, options, initialState, onSelectionChange, searchable = true, multiSelect = true) {
    if (!container) return;

    container.innerHTML = "";
    container.style.padding = "0";
    container.style.overflow = "hidden";

    let currentState = multiSelect
      ? (Array.isArray(initialState) ? [...initialState] : [])
      : initialState;

    let searchInput = null;
    if (searchable) {
      const searchContainer = document.createElement("div");
      searchContainer.className = "dropdown-search-zone";

      searchInput = document.createElement("input");
      searchInput.type = "text";
      searchInput.className = "popover-search";
      searchInput.placeholder = "Search...";

      searchContainer.appendChild(searchInput);
      container.appendChild(searchContainer);
    }

    const listZone = document.createElement("div");
    listZone.className = "dropdown-scroll-zone";

    if (multiSelect) {
      const actionZone = document.createElement("div");
      actionZone.className = "dropdown-action-zone";

      const selectAllBtn = document.createElement("button");
      selectAllBtn.className = "dropdown-action-btn";
      selectAllBtn.textContent = "Select all";

      const clearBtn = document.createElement("button");
      clearBtn.className = "dropdown-action-btn";
      clearBtn.textContent = "Clear";

      actionZone.appendChild(selectAllBtn);
      actionZone.appendChild(clearBtn);
      container.appendChild(actionZone);

      selectAllBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        e.preventDefault();
        currentState = options.filter(o => o.value !== "all").map(o => o.value);
        onSelectionChange([...currentState]);
        renderList(searchInput ? searchInput.value : "");
      });

      clearBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        e.preventDefault();
        currentState = [];
        onSelectionChange([...currentState]);
        renderList(searchInput ? searchInput.value : "");
      });
    }

    container.appendChild(listZone);

    const renderList = (filter = "") => {
      listZone.innerHTML = "";

      const filteredOptions = options.filter(opt =>
        opt.name.toLowerCase().includes(filter.toLowerCase())
      );

      if (filteredOptions.length === 0) {
        listZone.innerHTML = `<div class="p-4 text-xs text-gray-400 text-center">No options found</div>`;
        return;
      }

      filteredOptions.forEach(opt => {
        const isChecked = multiSelect ? currentState.includes(opt.value) : currentState === opt.value;

        const item = document.createElement("label");
        item.className = "popover-item";

        if (multiSelect) {
          const checkbox = document.createElement("input");
          checkbox.type = "checkbox";
          checkbox.className = "popover-checkbox";
          checkbox.checked = isChecked;
          item.appendChild(checkbox);

          item.addEventListener("change", (e) => {
            e.stopPropagation();
            if (checkbox.checked) {
              if (!currentState.includes(opt.value)) currentState.push(opt.value);
            } else {
              currentState = currentState.filter(v => v !== opt.value);
            }
            onSelectionChange([...currentState]);
          });
        } else {
          if (isChecked) {
            item.classList.add('active-popover-item');
          }

          item.addEventListener("click", (e) => {
            e.preventDefault();
            e.stopPropagation();
            currentState = opt.value;
            onSelectionChange(currentState);
            renderList(searchInput ? searchInput.value : "");

            // Close dropdown
            const dropdownMenu = container.closest('.dropdown-menu');
            if (dropdownMenu) {
              dropdownMenu.classList.add('hidden');
              const btn = document.querySelector(`[aria-controls="${dropdownMenu.id}"]`) || dropdownMenu.previousElementSibling;
              if (btn) btn.setAttribute('aria-expanded', 'false');
            }
          });
        }

        const text = document.createElement("span");
        text.textContent = opt.name;
        item.appendChild(text);

        listZone.appendChild(item);
      });
    };

    if (searchInput) {
      searchInput.addEventListener("input", (e) => {
        renderList(e.target.value);
      });
    }

    renderList();
  }
};

/**
 * Main application object.
 * DEPENDENCY: Requires 'TEST_DATA_SOURCE' to be defined in data.js
 */
const TestReportApp = {
  state: {
    viewMode: 'tree',
    currentFlatView: 'modules',
    filters: { variants: [], search: '', status: 'all' },
    sort: { by: 'name', order: 'asc' },
    variants: [],
    processedData: null
  },
  elements: {},

  init: function () {
    this.cacheDOMElements();

    // Directly access the global variable from data.js
    if (typeof TEST_DATA_SOURCE !== 'undefined') {
      this.setupTestResults(TEST_DATA_SOURCE);
      this.populateFilters();
      this.bindEvents();
      this.closeDropdownsOnClickOutside();
      this.render();
    } else {
      console.error("TEST_DATA_SOURCE is not defined. Make sure data.js is loaded before script.js");
      this.elements.resultsData.innerHTML = `<tr><td colspan="100%" class="text-center text-red font-bold" style="padding: 2rem;">Error: Data file not loaded.</td></tr>`;
    }
  },

  cacheDOMElements() {
    this.elements = {
      appTitle: document.getElementById('app-title'),
      reportDate: document.getElementById('report-date'),
      totalModules: document.getElementById('total-modules'),
      totalPackages: document.getElementById('total-packages'),
      totalClasses: document.getElementById('total-classes'),
      searchInput: document.getElementById('search-input'),

      viewModeBtn: document.getElementById('view-mode-btn'),
      viewModeText: document.getElementById('view-mode-text'),
      viewModeDropdown: document.getElementById('view-mode-dropdown'),
      viewModeList: document.getElementById('view-mode-list'),

      variantFilterBtn: document.getElementById('variant-filter-btn'),
      variantFilterText: document.getElementById('variant-filter-text'),
      variantFilterDropdown: document.getElementById('variant-filter-dropdown'),
      variantFilterList: document.getElementById('variant-filter-list'),

      statusFilterBtn: document.getElementById('status-filter-btn'),
      statusFilterText: document.getElementById('status-filter-text'),
      statusFilterDropdown: document.getElementById('status-filter-dropdown'),
      statusFilterList: document.getElementById('status-filter-list'),

      tableHeaders: document.getElementById('table-headers'),
      resultsData: document.getElementById('results-data'),
      breadcrumbs: document.getElementById('breadcrumbs'),
      viewToggles: document.getElementById('view-toggles'),
      flatViewControls: document.getElementById('flat-view-controls'),

      // Summary Card Elements
      totalTests: document.getElementById('total-tests'),
      totalPassed: document.getElementById('total-passed'),
      totalFailed: document.getElementById('total-failed'),
      totalSkipped: document.getElementById('total-skipped'),
      failedCard: document.getElementById('failed-card'),
    };
  },

  setupTestResults(testCaseData) {
    // Deep copy to avoid mutating the original source if used elsewhere
    const dataCopy = JSON.parse(JSON.stringify(testCaseData));
    this.state.variants = dataCopy.variants;
    this.state.filters.variants = [...dataCopy.variants]; // Default to all selected

    // Populate header
    if (this.elements.appTitle) this.elements.appTitle.textContent = dataCopy.projectName || 'Test Report';
    if (this.elements.reportDate) this.elements.reportDate.textContent = dataCopy.timestamp || '';
    if (this.elements.totalModules) this.elements.totalModules.textContent = dataCopy.numberOfModules || 0;
    if (this.elements.totalPackages) this.elements.totalPackages.textContent = dataCopy.numberOfPackages || 0;
    if (this.elements.totalClasses) this.elements.totalClasses.textContent = dataCopy.numberOfClasses || 0;

    const processNode = (node, type) => {
      node.type = type;
      const childKey = this.pluralize(this.getChildType(type));
      let children = node[childKey];

      if (type === 'class') {
        children = node.testCases || [];
      }

      if (children) {
        children.forEach(child => processNode(child, this.getChildType(type)));
      }

      node.summary = this._calculateSummaryFromChildren(children);
    };

    dataCopy.modules.forEach(module => processNode(module, 'module'));
    dataCopy.summary = this._calculateSummaryFromChildren(dataCopy.modules);
    this.processedData = dataCopy;
  },

  populateFilters() {
    // View Mode Dropdown
    const viewModeOptions = [
      { name: 'Tree View', value: 'tree' },
      { name: 'Flat View', value: 'flat' }
    ];
    UIUtils.buildActionDropdown(this.elements.viewModeList, viewModeOptions, this.state.viewMode, (newVal) => {
      this.state.viewMode = newVal;
      this.elements.viewModeText.textContent = viewModeOptions.find(o => o.value === newVal)?.name || 'Tree View';
      this.render();
    }, false, false);

    // Variants Dropdown
    const variantOptions = this.state.variants.map(v => ({ name: v, value: v }));
    UIUtils.buildActionDropdown(this.elements.variantFilterList, variantOptions, this.state.filters.variants, (newArr) => {
      this.state.filters.variants = newArr;
      this.updateVariantButtonText();
      this.render();
    }, true, true);

    // Status Dropdown
    const statusOptions = [
      { name: 'All Statuses', value: 'all' },
      { name: 'Passed', value: 'passed' },
      { name: 'Failed', value: 'failed' }
    ];
    UIUtils.buildActionDropdown(this.elements.statusFilterList, statusOptions, this.state.filters.status, (newVal) => {
      this.state.filters.status = newVal;
      this.elements.statusFilterText.textContent = statusOptions.find(o => o.value === newVal)?.name || 'All Statuses';
      this.render();
    }, false, false);
  },

  updateVariantButtonText() {
    const selectedCount = this.state.filters.variants.length;
    const totalCount = this.state.variants.length;

    if (selectedCount === 0) {
      this.elements.variantFilterText.textContent = 'None';
    } else if (selectedCount === totalCount) {
      this.elements.variantFilterText.textContent = 'All';
    } else if (selectedCount === 1) {
      this.elements.variantFilterText.textContent = this.state.filters.variants[0];
    } else {
      this.elements.variantFilterText.textContent = `${selectedCount} Variants`;
    }
  },

  // --- EVENT BINDING & HANDLING ---
  bindEvents() {
    this.elements.searchInput.addEventListener('input', () => { this.state.filters.search = this.elements.searchInput.value.trim(); this.render(); });

    this.elements.viewModeBtn.addEventListener('click', () => this.toggleDropdown(this.elements.viewModeDropdown, this.elements.viewModeBtn));
    this.elements.variantFilterBtn.addEventListener('click', () => this.toggleDropdown(this.elements.variantFilterDropdown, this.elements.variantFilterBtn));
    this.elements.statusFilterBtn.addEventListener('click', () => this.toggleDropdown(this.elements.statusFilterDropdown, this.elements.statusFilterBtn));

    this.elements.flatViewControls.addEventListener('click', (e) => { const button = e.target.closest('.view-toggle'); if (button) { this.state.currentFlatView = button.dataset.view; this.render(); } });
    this.elements.resultsData.addEventListener('click', (e) => {
        const treeToggle = e.target.closest('.tree-toggle');
        if (treeToggle) {
            e.stopPropagation();
            e.preventDefault();
            this.handleTreeRowClick(treeToggle);
        }
    });
    this.elements.tableHeaders.addEventListener('click', (e) => { const th = e.target.closest('[data-sort-by]'); if (!th) return; const newSortBy = th.dataset.sortBy; if (this.state.sort.by === newSortBy) { this.state.sort.order = this.state.sort.order === 'asc' ? 'desc' : 'asc'; } else { this.state.sort.by = newSortBy; this.state.sort.order = 'asc'; } this.render(); });

    // Failed Card interaction
    if (this.elements.failedCard) {
      this.elements.failedCard.classList.add('cursor-pointer');
      this.elements.failedCard.addEventListener('click', () => {
        this.state.viewMode = 'flat';
        this.state.currentFlatView = 'testCases';
        this.state.filters.status = 'failed';
        this.elements.viewModeText.textContent = 'Flat View';
        this.elements.statusFilterText.textContent = 'Failed';
        this.render();
      });
    }
  },

  toggleDropdown(dropdown, button) {
    const isHidden = dropdown.classList.contains('hidden');
    this.closeAllDropdowns();
    if (isHidden) {
        dropdown.classList.remove('hidden');
        button.setAttribute('aria-expanded', 'true');
    }
  },

  closeAllDropdowns() {
    this.elements.viewModeDropdown.classList.add('hidden');
    this.elements.viewModeBtn.setAttribute('aria-expanded', 'false');
    this.elements.variantFilterDropdown.classList.add('hidden');
    this.elements.variantFilterBtn.setAttribute('aria-expanded', 'false');
    this.elements.statusFilterDropdown.classList.add('hidden');
    this.elements.statusFilterBtn.setAttribute('aria-expanded', 'false');
  },

  closeDropdownsOnClickOutside() {
    document.addEventListener('click', (e) => {
      if (!this.elements.viewModeBtn.contains(e.target) && !this.elements.viewModeDropdown.contains(e.target) &&
          !this.elements.variantFilterBtn.contains(e.target) && !this.elements.variantFilterDropdown.contains(e.target) &&
          !this.elements.statusFilterBtn.contains(e.target) && !this.elements.statusFilterDropdown.contains(e.target)) {
        this.closeAllDropdowns();
      }
    });
  },

  handleTreeRowClick(target) {
    const row = target.closest('tr');
    if (!row) return;
    const arrow = row.querySelector('.collapsible-arrow');
    if (!arrow.classList.contains('invisible')) {
      arrow.classList.toggle('open');
      const isOpen = arrow.classList.contains('open');
      document.querySelectorAll(`[data-parent-id="${row.dataset.id}"]`).forEach(child => {
        child.classList.toggle('hidden', !isOpen);
        if (!isOpen) { const childArrow = child.querySelector('.collapsible-arrow.open'); if (childArrow) { childArrow.classList.remove('open'); this.collapseDescendants(child); } }
      });
    }
  },

  collapseDescendants(parentRow) {
    document.querySelectorAll(`[data-parent-id="${parentRow.dataset.id}"]`).forEach(child => {
      child.classList.add('hidden');
      const childArrow = child.querySelector('.collapsible-arrow.open');
      if (childArrow) { childArrow.classList.remove('open'); this.collapseDescendants(child); }
    });
  },

  // --- DATA PROCESSING & FILTERING ---
  getFilteredAndSortedData() {
    if (!this.processedData) return { modules: [] };
    const finalData = JSON.parse(JSON.stringify(this.processedData));

    const applyFilters = (nodes, type) => {
      if (!nodes) return [];
      return nodes.filter(node => {
        const childKey = this.pluralize(this.getChildType(type));
        let children = node[childKey] || (type === 'class' ? node.testCases : []);
        let hasVisibleChildren = false;

        // Filter children first
        if (children) {
          const filteredChildren = applyFilters(children, this.getChildType(type));
          if (type === 'class') node.testCases = filteredChildren;
          else node[childKey] = filteredChildren;
          hasVisibleChildren = filteredChildren.length > 0;
        }

        // Search filter
        let selfMatchesSearch = true;
        if (this.state.filters.search) {
          const searchTerm = this.state.filters.search.toLowerCase();
          selfMatchesSearch = node.name.toLowerCase().includes(searchTerm);
        }

        let matchesStatus = true;
        if (this.state.filters.status !== 'all') {
          const isLeaf = !this.getChildType(type);
          if (isLeaf) {
            // Check against SELECTED variants
            let hasFail = false;
            let hasPass = false;

            this.state.filters.variants.forEach(v => {
              const statusVal = node[v];
              const status = (typeof statusVal === 'object' && statusVal !== null) ? statusVal.status : statusVal;
              if (status === 'fail') hasFail = true;
              if (status === 'pass') hasPass = true;
            });

            if (this.state.filters.status === 'passed') matchesStatus = hasPass;
            if (this.state.filters.status === 'failed') matchesStatus = hasFail;
          } else {
            matchesStatus = hasVisibleChildren;
          }
        }

        if (this.state.filters.status !== 'all' && !this.getChildType(type)) {
          return matchesStatus && selfMatchesSearch;
        }

        if (this.state.filters.status !== 'all') {
          return hasVisibleChildren;
        }

        return selfMatchesSearch || hasVisibleChildren;
      });
    };

    const sortNodes = (nodes) => {
      if (!nodes) return;
      nodes.sort((a, b) => {
        const valA = a.name.toLowerCase();
        const valB = b.name.toLowerCase();
        if (valA < valB) return this.state.sort.order === 'asc' ? -1 : 1;
        if (valA > valB) return this.state.sort.order === 'asc' ? 1 : -1;
        return 0;
      });

      nodes.forEach(node => {
        const childKey = this.pluralize(this.getChildType(node.type));
        let children = node[childKey] || (node.type === 'class' ? node.testCases : []);
        if (children) sortNodes(children);
      });
    };

    finalData.modules = applyFilters(finalData.modules, 'module');
    if (this.state.sort.by === 'name') {
      sortNodes(finalData.modules);
    }

    return finalData;
  },

  // --- RENDERING ---
  render() {
    const data = this.getFilteredAndSortedData();
    this.elements.viewToggles.style.display = this.state.viewMode === 'flat' ? 'block' : 'none';
    this.updateSummaryCards();
    this.renderTable(data);
    if (this.state.viewMode === 'flat') {
      this.updateActiveTabs();
    }
  },

  updateSummaryCards() {
    if (!this.processedData || !this.processedData.summary) return;
    const sum = this.processedData.summary;
    if (this.elements.totalTests) this.elements.totalTests.textContent = sum.total;
    if (this.elements.totalPassed) this.elements.totalPassed.textContent = sum.passed;
    if (this.elements.totalFailed) this.elements.totalFailed.textContent = sum.failed;
    if (this.elements.totalSkipped) this.elements.totalSkipped.textContent = sum.skipped;
  },

  updateActiveTabs() {
    this.elements.flatViewControls.querySelectorAll('.view-toggle').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.view === this.state.currentFlatView);
    });
  },

  renderTable(data) {
    this.renderHeaders();
    this.renderBreadcrumbs();
    if (this.state.viewMode === 'tree') {
      this.renderTreeRows(data);
    } else {
      this.renderFlatRows(data);
    }
  },

  renderHeaders() {
    const variantsToShow = this.state.filters.variants;
    const sortIndicator = (key) => this.state.sort.by === key ? (this.state.sort.order === 'asc' ? '▲' : '▼') : '';
    let nameHeader = this.state.viewMode === 'tree' ? 'Name' : this.state.currentFlatView.charAt(0).toUpperCase() + this.state.currentFlatView.slice(1);

    this.elements.tableHeaders.innerHTML = `
            <tr>
                <th class="sticky-name" data-sort-by="name">${nameHeader} ${sortIndicator('name')}</th>
                ${variantsToShow.map(v => `<th class="text-center border-l" colspan="4">${v}</th>`).join('')}
            </tr>
            <tr>
                <th class="sticky-name"></th>
                ${variantsToShow.map(v => `<th class="text-center text-xs font-medium border-l">Pass</th><th class="text-center text-xs font-medium">Fail</th><th class="text-center text-xs font-medium">Skip</th><th class="text-center text-xs font-medium">Pass Rate</th>`).join('')}
            </tr>`;
  },

  renderBreadcrumbs() {
    this.elements.breadcrumbs.innerHTML = (this.state.viewMode === 'flat') ?
      `<span>Showing all ${this.state.currentFlatView}</span>` :
      `<span class="font-medium">Project Overview</span>`;
  },

  renderTreeRows(data) {
    let html = '';
    const renderNode = (node, parentId, level) => {
      const type = node.type;
      const childType = this.getChildType(type);
      const childKey = this.pluralize(childType);
      const children = node[childKey] || (type === 'class' ? node.testCases : []) || [];
      const hasChildren = children.length > 0;
      const uniqueId = `${parentId}-${node.name}`.replace(/[^a-zA-Z0-9-_]/g, '');

      const nameContent = `<span class="font-medium">${node.name}</span>`;
      const chevron = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="collapsible-arrow ${!hasChildren ? 'invisible' : ''}"><path d="m9 18 6-6-6-6"></path></svg>`;

      html += `
                <tr class="table-row ${level > 0 ? 'hidden' : ''}" data-id="${uniqueId}" data-parent-id="${parentId}">
                    <td class="sticky-name" title="${node.name}">
                        <div class="tree-toggle" style="padding-left: ${level * 1.0}rem;">
                            ${chevron} ${nameContent}
                        </div>
                    </td>
                    ${this._renderStatusCell(type === 'testCase' ? node : node.summary, type === 'testCase')}
                </tr>`;

      if (hasChildren) {
        children.forEach(child => renderNode(child, uniqueId, level + 1));
      }
    };

    if (data.modules) data.modules.forEach(module => renderNode(module, 'root', 0));
    this.elements.resultsData.innerHTML = html || '<tr><td colspan="100%" class="text-center text-gray" style="padding: 2rem;">No items match the current filters.</td></tr>';
  },

  renderFlatRows(data) {
    let items = [];
    const view = this.state.currentFlatView;
    if (data.modules) {
        if (view === 'modules') items = data.modules;
        else if (view === 'packages') items = data.modules.flatMap(m => m.packages.map(i => ({ ...i, parent: m.name })));
        else if (view === 'classes') items = data.modules.flatMap(m => m.packages.flatMap(p => p.classes.map(i => ({ ...i, parent: p.name }))));
        else if (view === 'testCases') items = data.modules.flatMap(m => m.packages.flatMap(p => p.classes.flatMap(c => c.testCases.map(i => ({ ...i, parent: c.name })))));
    }

    this.elements.resultsData.innerHTML = items.map(item => `
            <tr class="table-row">
                <td class="sticky-name" title="${item.name}${item.parent ? ' (' + item.parent + ')' : ''}">
                     <div class="font-medium">${item.name}</div>
                     ${item.parent ? `<div class="text-xs text-gray">${item.parent}</div>` : ''}
                </td>
                ${this._renderStatusCell(view === 'testCases' ? item : item.summary, view === 'testCases')}
            </tr>`).join('') || '<tr><td colspan="100%" class="text-center text-gray" style="padding: 2rem;">No results found.</td></tr>';
  },

  // --- HELPERS ---
  _calculateSummaryFromChildren(children) {
    if (!children || children.length === 0) {
      // Return empty summary with 0s
      const summary = { total: 0, passed: 0, failed: 0, skipped: 0, passRate: 0 };
      this.state.variants.forEach(v => {
        summary[v] = { passed: 0, failed: 0, skipped: 0, total: 0, rate: 0 };
      });
      return summary;
    }

    const summary = { total: 0, passed: 0, failed: 0, skipped: 0, passRate: 0 };
    this.state.variants.forEach(v => {
      summary[v] = { passed: 0, failed: 0, skipped: 0, total: 0, rate: 0 };
    });

    children.forEach(child => {
      this.state.variants.forEach(v => {
        if (child.type === 'testCase') {
          const statusVal = child[v];
          const status = (typeof statusVal === 'object' && statusVal !== null) ? statusVal.status : statusVal;
          if (status === 'pass') summary[v].passed++;
          else if (status === 'fail') summary[v].failed++;
          else if (status === 'skipped') summary[v].skipped++;
          summary[v].total++;
        } else {
          // It's a node with summary
          summary[v].passed += child.summary[v].passed;
          summary[v].failed += child.summary[v].failed;
          summary[v].skipped += child.summary[v].skipped;
          summary[v].total += child.summary[v].total;
        }
      });
    });

    // Calculate rates
    let totalPassed = 0;
    let totalFailed = 0;
    let totalSkipped = 0;

    this.state.variants.forEach(v => {
      const s = summary[v];
      const relevant = s.passed + s.failed;
      s.rate = relevant > 0 ? (s.passed / relevant) * 100 : 100; // Default to 100 if no tests? Or 0?
      // If total is 0, rate is 0?
      if (s.total === 0) s.rate = 0;

      totalPassed += s.passed;
      totalFailed += s.failed;
      totalSkipped += s.skipped;
    });
    summary.passed = totalPassed;
    summary.failed = totalFailed;
    summary.skipped = totalSkipped;

    summary.total = summary.passed + summary.failed + summary.skipped;
    const relevantTotal = summary.passed + summary.failed;
    summary.passRate = relevantTotal > 0 ? (summary.passed / relevantTotal) * 100 : 100;
    return summary;
  },

  _renderStatusCell(summaryOrNode, isNode = false) {
    // If isNode is true, summaryOrNode is the node itself (for functions), otherwise it's a summary object
    if (!summaryOrNode) {
      const colspan = (this.state.filters.variants.length) * 4;
      return `<td colspan="${colspan}"></td>`;
    }

    const variantsToShow = this.state.filters.variants;

    if (isNode) {
      // Rendering for a function row - show direct status
      return `${variantsToShow.map(v => {
        const statusVal = summaryOrNode[v];
        const status = (typeof statusVal === 'object' && statusVal !== null) ? statusVal.status : statusVal;
        const stackTrace = (typeof statusVal === 'object' && statusVal !== null) ? statusVal.stackTrace : null;

        let cellContent = '-';
        let cellClass = 'text-center text-gray border-l';

        if (status === 'pass') {
          cellContent = 'Passed';
          cellClass = 'text-center text-green font-medium border-l';
        } else if (status === 'fail') {
          if (stackTrace) {
            cellContent = 'Failure';
            cellClass = 'text-center text-red font-bold border-l clickable-status';
            return `<td colspan="4" class="${cellClass}" onclick="TestReportApp.openStackTrace(this)" data-stack-trace="${encodeURIComponent(stackTrace)}">${cellContent}</td>`;
          } else {
            cellContent = 'Failed';
            cellClass = 'text-center text-red font-bold border-l';
          }
        } else if (status === 'skipped') {
          cellContent = 'Skipped';
          cellClass = 'text-center text-yellow border-l';
        }

        return `<td colspan="4" class="${cellClass}">${cellContent}</td>`;
      }).join('')}`;
    }

    // Rendering for a summary row
    return `${variantsToShow.map(v => {
      const stats = summaryOrNode[v];
      if (!stats) return '<td colspan="4" class="text-center text-gray border-l">-</td>';

      const { passed, failed, skipped, total, rate } = stats;
      const passRateColor = rate >= 95 ? 'text-green' : rate >= 80 ? 'text-yellow' : 'text-red';
      const relevantTotal = passed + failed;

      const filter = this.state.filters.status;
      const showPassed = filter === 'all' || filter === 'passed';
      const showFailed = filter === 'all' || filter === 'failed';
      const showSkipped = filter === 'all' || filter === 'skipped';

      return `
                <td class="text-center ${showPassed ? 'text-green' : 'text-gray'} font-medium border-l">${showPassed ? passed : '-'}</td>
                <td class="text-center ${showFailed ? (failed > 0 ? 'text-red font-bold' : 'text-gray') : 'text-gray'}">${showFailed ? failed : '-'}</td>
                <td class="text-center ${showSkipped ? 'text-yellow' : 'text-gray'}">${showSkipped ? skipped : '-'}</td>
                <td class="text-center">
                    <div class="flex-col">
                        <span class="font-bold ${passRateColor}">${rate.toFixed(1)}%</span>
                        <span class="text-xs text-gray">${passed}/${relevantTotal}</span>
                    </div>
                </td>`;
    }).join('')}`;
  },

  openStackTrace(element) {
    const stackTrace = decodeURIComponent(element.dataset.stackTrace);
    const modal = document.getElementById('stack-trace-modal');
    const content = document.getElementById('stack-trace-content');
    content.textContent = stackTrace;
    modal.classList.remove('hidden');
  },

  getChildType(parentType) {
    const hierarchy = { 'root': 'module', 'module': 'package', 'package': 'class', 'class': 'testCase', 'testCase': null };
    return hierarchy[parentType];
  },

  pluralize(type) {
    const pluralMap = { 'module': 'modules', 'package': 'packages', 'class': 'classes', 'testCase': 'testCases' };
    return pluralMap[type];
  }
};

document.addEventListener('DOMContentLoaded', () => {
  TestReportApp.init();

  // Modal close handlers
  const modal = document.getElementById('stack-trace-modal');
  const closeBtn = document.getElementById('close-modal');

  if (closeBtn) {
    closeBtn.addEventListener('click', () => {
      modal.classList.add('hidden');
    });
  }

  if (modal) {
    modal.addEventListener('click', (e) => {
      if (e.target === modal) {
        modal.classList.add('hidden');
      }
    });
  }

  // Close on Escape key
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      TestReportApp.closeAllDropdowns();
      if (modal && !modal.classList.contains('hidden')) {
        modal.classList.add('hidden');
      }
    }
  });
});
