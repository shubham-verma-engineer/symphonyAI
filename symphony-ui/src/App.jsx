import React, { useState, useEffect } from 'react';

const API_BASE = 'http://localhost:8091/api/v1';

// SVGs
const SearchIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
);
const UploadIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
);
const SunIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>
);
const MoonIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>
);
const GridIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="7" height="9"></rect><rect x="14" y="3" width="7" height="5"></rect><rect x="14" y="12" width="7" height="9"></rect><rect x="3" y="16" width="7" height="5"></rect></svg>
);
const ChatIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>
);
const ChartIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="20" x2="18" y2="10"></line><line x1="12" y1="20" x2="12" y2="4"></line><line x1="6" y1="20" x2="6" y2="14"></line></svg>
);

function App() {
  const [view, setView] = useState('marketplace'); // 'marketplace' | 'onboard' | 'chat' | 'analytics'
  const [theme, setTheme] = useState(() => localStorage.getItem('symphony-theme') || 'dark');
  const [userRole, setUserRole] = useState(() => localStorage.getItem('symphony-role') || 'Employee');
  const [token, setToken] = useState(() => localStorage.getItem('symphony-jwt') || '');
  const [workspaceName, setWorkspaceName] = useState(() => localStorage.getItem('symphony-workspace-name') || 'default_ws');
  
  const [agents, setAgents] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('All');
  const [loading, setLoading] = useState(false);
  const [manifestText, setManifestText] = useState(JSON.stringify({
    "manifestVersion": "1.0",
    "name": "payroll-manager",
    "version": "1.0.0",
    "description": "Reviews corporate staff payroll files and executes salary disbursement.",
    "endpoints": {
      "execution": "https://payroll.internal.net/api/v1/disburse",
      "health": "https://payroll.internal.net/health"
    },
    "parameters": [
      {
        "name": "departmentCode",
        "type": "string",
        "description": "Department code (e.g. ENG, HR)",
        "required": true
      }
    ],
    "security": {
      "requiresRBAC": true,
      "authorizedRoles": ["Admin", "HR"],
      "secretKey": "mock-secret-agent-key"
    }
  }, null, 2));

  const [onboardLogs, setOnboardLogs] = useState([]);
  const [selectedAgent, setSelectedAgent] = useState(null);

  // Chat/Supervisor state
  const [chatInput, setChatInput] = useState('');
  const [chatMessages, setChatMessages] = useState([
    { sender: 'supervisor', text: 'Hello! I am the SymphonyAI Supervisor. Explain your requirements, and I will route them to the appropriate enterprise agent.', time: new Date().toLocaleTimeString(), routing: null }
  ]);
  const [selectedRoutingMetadata, setSelectedRoutingMetadata] = useState(null);
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const [chatLoading, setChatLoading] = useState(false);

  // Gaps & Telemetry state
  const [gaps, setGaps] = useState([]);
  const [telemetry, setTelemetry] = useState([]);
  const [clusteringLoading, setClusteringLoading] = useState(false);
  const [billingStats, setBillingStats] = useState([]);
  const [billingAudit, setBillingAudit] = useState([]);

  // Sync theme
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('symphony-theme', theme);
  }, [theme]);

  // Sync userRole and fetch matching JWT session token
  useEffect(() => {
    localStorage.setItem('symphony-role', userRole);
    const fetchToken = async () => {
      try {
        const res = await fetch(`${API_BASE}/auth/login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: 'shubham', role: userRole })
        });
        if (res.ok) {
          const data = await res.json();
          setToken(data.token);
          setWorkspaceName(data.workspaceName || 'default_ws');
          localStorage.setItem('symphony-jwt', data.token);
          localStorage.setItem('symphony-workspace-name', data.workspaceName || 'default_ws');
          console.log(`Successfully obtained JWT for role: ${userRole}`);
        }
      } catch (err) {
        console.error("Failed to authenticate SSO", err);
      }
    };
    fetchToken();
  }, [userRole]);

  const toggleTheme = () => {
    setTheme(prev => prev === 'dark' ? 'light' : 'dark');
  };

  // Fetch agents
  const fetchAgents = async () => {
    setLoading(true);
    try {
      const headers = {};
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const res = await fetch(`${API_BASE}/agents`, { headers });
      if (res.ok) {
        setAgents(await res.json());
      }
    } catch (err) {
      console.error("Failed loading agents", err);
    } finally {
      setLoading(false);
    }
  };

  // Fetch gaps, telemetry and billing stats
  const fetchAnalytics = async () => {
    try {
      const headers = {};
      if (token) headers['Authorization'] = `Bearer ${token}`;
      
      const resGaps = await fetch(`${API_BASE}/admin/gaps`, { headers });
      if (resGaps.ok) setGaps(await resGaps.json());

      const resTel = await fetch(`${API_BASE}/admin/telemetry`, { headers });
      if (resTel.ok) setTelemetry(await resTel.json());

      const resBillingStats = await fetch(`${API_BASE}/admin/billing/stats`, { headers });
      if (resBillingStats.ok) setBillingStats(await resBillingStats.json());

      const resBillingAudit = await fetch(`${API_BASE}/admin/billing/audit`, { headers });
      if (resBillingAudit.ok) setBillingAudit(await resBillingAudit.json());
    } catch (err) {
      console.error("Failed loading analytics and billing data", err);
    }
  };

  useEffect(() => {
    if (view === 'marketplace') {
      fetchAgents();
      setSelectedAgent(null);
    } else if (view === 'analytics' || view === 'billing') {
      fetchAnalytics();
    }
  }, [view, token]);

  // Search agents
  const handleSearch = async (e) => {
    e.preventDefault();
    if (!searchQuery.trim()) {
      fetchAgents();
      return;
    }
    setLoading(true);
    try {
      const headers = { 'Content-Type': 'application/json' };
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const res = await fetch(`${API_BASE}/agents/search`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ query: searchQuery, limit: 10 })
      });
      if (res.ok) {
        setAgents(await res.json());
      }
    } catch (err) {
      console.error("Search failed", err);
    } finally {
      setLoading(false);
    }
  };

  // Onboard agent manifest
  const handleOnboard = async (e) => {
    e.preventDefault();
    setOnboardLogs([]);
    const addLog = (text, type = 'info') => {
      setOnboardLogs(prev => [...prev, { text, type, time: new Date().toLocaleTimeString() }]);
    };

    try {
      addLog("Parsing manifest payload...", "info");
      let parsed = JSON.parse(manifestText);
      addLog(`Validating JSON Schema parameters for '${parsed.name || 'unnamed'}'...`, "info");
      if (!parsed.name || !parsed.description || !parsed.endpoints) {
        addLog("Schema Error: Missing core properties.", "error");
        return;
      }
      addLog("Validation PASS: Schema matches specifications.", "success");

      if (parsed.security && parsed.security.requiresRBAC) {
        addLog(`Security config: RBAC active, limited to: ${JSON.stringify(parsed.security.authorizedRoles)}`, "info");
      }

      addLog(`Loopback Check: Querying health at ${parsed.endpoints.health}...`, "info");
      
      const headers = { 'Content-Type': 'application/json' };
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const res = await fetch(`${API_BASE}/agents/onboard`, {
        method: 'POST',
        headers,
        body: JSON.stringify(parsed)
      });

      if (res.ok) {
        const data = await res.json();
        addLog(`Health Status: Endpoint active (HTTP 200 OK).`, "success");
        addLog(`Embedding: Vector representation generated.`, "success");
        addLog(`Registry: Agent '${data.name}' registered (ID: ${data.id})`, "success");
        addLog("Onboarding success.", "success");
      } else {
        addLog(`Verification Failed: Endpoint unreachable or validation mismatch.`, "error");
      }
    } catch (err) {
      addLog(`Network Failure: ${err.message}`, "error");
    }
  };

  // Conversational Supervisor routing
  const handleChatSend = async (e) => {
    e.preventDefault();
    if (!chatInput.trim()) return;

    const userQuery = chatInput;
    setChatInput('');
    setChatMessages(prev => [...prev, { sender: 'user', text: userQuery, time: new Date().toLocaleTimeString() }]);
    setChatLoading(true);

    try {
      const headers = { 'Content-Type': 'application/json' };
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const res = await fetch(`${API_BASE}/supervisor/chat`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ query: userQuery })
      });

      if (res.ok) {
        const data = await res.json();
        const msg = {
          sender: 'supervisor',
          text: data.textResponse,
          time: new Date().toLocaleTimeString(),
          piiSanitized: data.piiSanitized,
          sanitizedFields: data.sanitizedFields,
          routing: {
            agent: data.matchedAgent,
            params: data.extractedParameters,
            latency: data.latencyMs,
            status: data.status,
            executionSteps: data.executionSteps
          }
        };
        setChatMessages(prev => [...prev, msg]);
        setSelectedRoutingMetadata(msg.routing);
        setInspectorOpen(true);
      }
    } catch (err) {
      console.error("Chat failure", err);
    } finally {
      setChatLoading(false);
    }
  };

  // Trigger manual gap clustering
  const triggerClustering = async () => {
    setClusteringLoading(true);
    try {
      const headers = {};
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const res = await fetch(`${API_BASE}/admin/gaps/trigger-cluster`, { method: 'POST', headers });
      if (res.ok) {
        await fetchAnalytics();
        alert("Innovation gap clustering completed successfully!");
      }
    } catch (err) {
      console.error("Clustering trigger failed", err);
    } finally {
      setClusteringLoading(false);
    }
  };

  const filteredAgents = agents.filter(a => {
    if (selectedCategory === 'All') return true;
    const name = a.name.toLowerCase();
    const desc = a.description.toLowerCase();
    if (selectedCategory === 'Legal') return name.includes('legal') || desc.includes('legal');
    if (selectedCategory === 'HR') return name.includes('hr') || name.includes('payroll') || desc.includes('payroll');
    if (selectedCategory === 'Finance') return name.includes('finance') || desc.includes('finance');
    if (selectedCategory === 'DevTools') return name.includes('summary') || name.includes('parse') || desc.includes('summar');
    return true;
  });

  // Calculate aggregated analytics parameters
  const totalUnaddressedHits = gaps.reduce((acc, g) => acc + g.countFrequency, 0);
  const avgLatency = telemetry.length > 0 
      ? Math.round(telemetry.reduce((acc, t) => acc + t.avglatency, 0) / telemetry.length) 
      : 0;

  const totalBillingCost = billingAudit.reduce((acc, b) => acc + (b.tokenCost || 0), 0);
  const totalBillingCostUsd = parseFloat(totalBillingCost).toFixed(5);
  const avgBillingCostUsd = billingAudit.length > 0 
      ? parseFloat(totalBillingCost / billingAudit.length).toFixed(6) 
      : "0.000000";

  return (
    <div className="app-container">
      {/* Structural Persistent Left Sidebar */}
      <aside className="sidebar">
        <div className="brand-wrapper" style={{ marginBottom: '2rem' }}>
          <img src="/logo.jpg" alt="SymphonyAI Logo" style={{ width: '28px', height: '28px', borderRadius: 'var(--radius-md)', objectFit: 'cover' }} />
          <span className="brand-title premium-gradient-text">SymphonyAI</span>
        </div>

        <span className="nav-group-title">Enterprise Tools</span>
        <nav>
          <ul className="nav-links">
            <li className={`nav-item ${view === 'marketplace' ? 'active' : ''}`} onClick={() => setView('marketplace')}>
              <GridIcon />
              <span>Registry Hub</span>
            </li>
            <li className={`nav-item ${view === 'chat' ? 'active' : ''}`} onClick={() => setView('chat')}>
              <ChatIcon />
              <span>Supervisor Chat</span>
            </li>
          </ul>
        </nav>

        <span className="nav-group-title">Console Admin</span>
        <nav>
          <ul className="nav-links">
            <li className={`nav-item ${view === 'onboard' ? 'active' : ''}`} onClick={() => setView('onboard')}>
              <UploadIcon />
              <span>Onboard Agent</span>
            </li>
            <li className={`nav-item ${view === 'analytics' ? 'active' : ''}`} onClick={() => setView('analytics')}>
              <ChartIcon />
              <span>Demand & Gaps</span>
            </li>
            <li className={`nav-item ${view === 'billing' ? 'active' : ''}`} onClick={() => setView('billing')}>
              <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="1" x2="12" y2="23"></line><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"></path></svg>
              <span>Billing & Cost</span>
            </li>
          </ul>
        </nav>
      </aside>

      {/* Main Content Area */}
      <div className={`main-content-layout ${view === 'chat' ? 'chat-view-layout' : ''}`}>
        
        {/* Top Header Row */}
        <header className="top-header">
          <div className="workspace-pill">
            <span style={{ width: '6px', height: '6px', borderRadius: '50%', backgroundColor: 'var(--success)' }}></span>
            <span>{workspaceName}</span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '1.25rem' }}>
            {/* User SSO Role */}
            <div className="role-selector-wrapper">
              <select value={userRole} onChange={e => setUserRole(e.target.value)}>
                <option value="Employee">Employee (Standard)</option>
                <option value="HR">HR Specialist</option>
                <option value="Admin">Administrator</option>
              </select>
            </div>

            {/* Theme Toggle Button */}
            <button className="icon-btn" onClick={toggleTheme} title="Toggle theme">
              {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
            </button>

            <span style={{ fontSize: '0.85rem', fontWeight: '500', color: 'var(--text-secondary)' }}>shubham</span>
          </div>
        </header>

        {/* Dynamic Canvas Container */}
        <div className={`canvas-wrapper ${view === 'chat' ? 'chat-canvas-wrapper' : ''}`}>
          
          {/* VIEW 1: REGISTRY HUB */}
          {view === 'marketplace' && (
            <>
              <div className="section-header">
                <h1 className="page-title premium-gradient-text">Internal Registry</h1>
                <p className="page-desc">Search and discover all employee-built AI tools registered inside your enterprise workspace.</p>
              </div>

              <div style={{ display: 'flex', gap: '0.75rem', flexDirection: 'column', marginBottom: '2rem' }}>
                <form onSubmit={handleSearch} className="search-container" style={{ maxWidth: '600px' }}>
                  <input 
                    type="text" 
                    className="search-input"
                    placeholder="Search agents semantically (e.g. 'process payroll')"
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                  />
                  <button type="submit" className="btn btn-primary" style={{ border: 'none' }}><SearchIcon /></button>
                </form>

                <div className="category-pills" style={{ marginTop: '0.5rem' }}>
                  {['All', 'Legal', 'HR', 'Finance', 'DevTools'].map(cat => (
                    <span key={cat} className={`pill ${selectedCategory === cat ? 'active' : ''}`} onClick={() => setSelectedCategory(cat)}>
                      {cat}
                    </span>
                  ))}
                </div>
              </div>

              {loading ? (
                <div style={{ padding: '4rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Querying database...</div>
              ) : (
                <div className="agent-grid">
                  {filteredAgents.length === 0 ? (
                    <div style={{ gridColumn: '1/-1', padding: '4rem', textAlign: 'center', color: 'var(--text-secondary)', border: '1px dashed var(--border-color)', borderRadius: 'var(--radius-lg)' }}>
                      No matching agents found. Try registering a new manifest!
                    </div>
                  ) : (
                    filteredAgents.map(agent => (
                      <div key={agent.id} className="premium-card" onClick={() => setSelectedAgent(agent)}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                          <div style={{ minWidth: 0 }}>
                            <h3 style={{ fontSize: '0.975rem', fontWeight: '600', color: 'var(--text-primary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{agent.name}</h3>
                            <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>v{agent.version}</span>
                          </div>
                          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '0.2rem' }}>
                            <span className={`status-pill ${agent.status === 'ACTIVE' ? 'status-active' : 'status-unhealthy'}`}>
                              <span className="status-dot"></span>
                              {agent.status}
                            </span>
                            {agent.requiresRbac && (
                              <span className="badge" style={{ backgroundColor: 'rgba(186, 26, 26, 0.08)', color: 'var(--error)', border: '1px solid rgba(186, 26, 26, 0.15)', fontSize: '0.65rem', padding: '0.1rem 0.3rem', borderRadius: '3px' }}>
                                RBAC
                              </span>
                            )}
                          </div>
                        </div>
                        <p style={{ fontSize: '0.825rem', color: 'var(--text-secondary)', flexGrow: 1, overflow: 'hidden', display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical', lineHeight: '1.4', margin: '0.75rem 0' }}>
                          {agent.description}
                        </p>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderTop: '1px solid var(--border-muted)', paddingTop: '0.6rem' }}>
                          {agent.similarity < 1.0 && agent.similarity > 0 ? (
                            <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                              Match: <strong>{Math.round(agent.similarity * 100)}%</strong>
                            </span>
                          ) : (
                            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Semantic Match</span>
                          )}
                          <span style={{ fontSize: '0.75rem', color: 'var(--accent-secondary)', fontWeight: '600' }}>Inspect Details</span>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              )}

              {/* Agent Detail Modal */}
              {selectedAgent && (
                <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }} onClick={() => setSelectedAgent(null)}>
                  <div className="premium-card" style={{ width: '100%', maxWidth: '520px', padding: '2rem', height: 'auto', display: 'flex', flexDirection: 'column', gap: '1.25rem', margin: '1rem', background: 'var(--bg-secondary)', borderColor: 'var(--border-color)', cursor: 'default' }} onClick={e => e.stopPropagation()}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <h2 style={{ fontSize: '1.25rem', fontWeight: '600' }}>{selectedAgent.name}</h2>
                      <span className="status-pill status-active"><span className="status-dot"></span>{selectedAgent.status}</span>
                    </div>
                    <div>
                      <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.3rem', fontSize: '0.75rem', textTransform: 'uppercase', fontWeight: '600' }}>Description</h4>
                      <p style={{ fontSize: '0.85rem', lineHeight: '1.4' }}>{selectedAgent.description}</p>
                    </div>
                    {selectedAgent.requiresRbac && (
                      <div>
                        <h4 style={{ color: 'var(--error)', marginBottom: '0.3rem', fontSize: '0.75rem', textTransform: 'uppercase', fontWeight: '600' }}>Governance Rules</h4>
                        <p style={{ fontSize: '0.825rem' }}>
                          Execution restricted to roles: 
                          <strong style={{ marginLeft: '0.4rem' }}>{selectedAgent.authorizedRoles?.join(', ') || 'None'}</strong>
                        </p>
                      </div>
                    )}
                    <div>
                      <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.3rem', fontSize: '0.75rem', textTransform: 'uppercase', fontWeight: '600' }}>Endpoints</h4>
                      <div style={{ fontFamily: 'var(--font-mono)', fontSize: '0.75rem', display: 'flex', flexDirection: 'column', gap: '0.25rem', backgroundColor: 'var(--bg-primary)', padding: '0.6rem 0.8rem', borderRadius: 'var(--radius-md)', border: '1px solid var(--border-color)' }}>
                        <div>Execution: <span style={{ color: 'var(--text-muted)' }}>{selectedAgent.endpointUrl}</span></div>
                        <div>Health: <span style={{ color: 'var(--text-muted)' }}>{selectedAgent.healthCheckUrl}</span></div>
                      </div>
                    </div>
                    <div>
                      <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.4rem', fontSize: '0.75rem', textTransform: 'uppercase', fontWeight: '600' }}>Parameters Schema</h4>
                      {selectedAgent.parameters && selectedAgent.parameters.length > 0 ? (
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.75rem' }}>
                          <thead>
                            <tr style={{ borderBottom: '1px solid var(--border-color)', textAlign: 'left', color: 'var(--text-secondary)' }}>
                              <th style={{ padding: '0.4rem 0' }}>Name</th>
                              <th style={{ padding: '0.4rem 0' }}>Type</th>
                              <th style={{ padding: '0.4rem 0' }}>Required</th>
                            </tr>
                          </thead>
                          <tbody>
                            {selectedAgent.parameters.map(p => (
                              <tr key={p.name} style={{ borderBottom: '1px solid var(--border-muted)' }}>
                                <td style={{ padding: '0.4rem 0', fontFamily: 'monospace' }}>{p.name}</td>
                                <td style={{ padding: '0.4rem 0', color: 'var(--text-muted)' }}>{p.type}</td>
                                <td style={{ padding: '0.4rem 0' }}>{p.required ? 'Yes' : 'No'}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      ) : (
                        <p style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>No parameter configuration specified.</p>
                      )}
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.5rem' }}>
                      <button className="btn" onClick={() => setSelectedAgent(null)}>Close</button>
                    </div>
                  </div>
                </div>
              )}
            </>
          )}

          {/* VIEW 2: ONBOARDING */}
          {view === 'onboard' && (
            <>
              <div className="section-header">
                <h1 className="page-title premium-gradient-text">Onboard Agent</h1>
                <p className="page-desc">Validate, trace, and index developer-hosted agent manifests inside the workspace catalog.</p>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr', gap: '2rem', alignItems: 'start' }}>
                <div className="ingest-drop-zone">
                  <div style={{ width: '100%', textAlign: 'left' }}>
                    <label style={{ fontSize: '0.8rem', fontWeight: '600', color: 'var(--text-secondary)', display: 'block', marginBottom: '0.5rem' }}>Agent Manifest Spec (JSON)</label>
                    <textarea 
                      rows="14" 
                      className="form-control"
                      style={{ fontFamily: 'var(--font-mono)', fontSize: '0.8rem', resize: 'vertical', width: '100%', backgroundColor: 'var(--bg-secondary)' }}
                      value={manifestText}
                      onChange={e => setManifestText(e.target.value)}
                    />
                  </div>
                  <button onClick={handleOnboard} className="btn btn-primary" style={{ alignSelf: 'flex-start' }}>
                    <UploadIcon />
                    <span>Upload & Register</span>
                  </button>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                  <h3 style={{ fontSize: '0.9rem', fontWeight: '600', color: 'var(--text-secondary)' }}>Verification Stream Logs</h3>
                  <div className="terminal-console" style={{ height: '370px' }}>
                    {onboardLogs.length === 0 ? (
                      <div style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>Logs will display here...</div>
                    ) : (
                      onboardLogs.map((log, idx) => (
                        <div key={idx} className={`terminal-line ${log.type}`}>
                          <span style={{ color: 'var(--text-muted)', marginRight: '0.5rem' }}>[{log.time}]</span>
                          <span>{log.text}</span>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </div>
            </>
          )}

          {/* VIEW 3: SUPERVISOR CHAT */}
          {view === 'chat' && (
            <>
              <div className="section-header">
                <h1 className="page-title premium-gradient-text">Supervisor Chat</h1>
                <p className="page-desc">Decompose prompts, resolve schemas, and chain multi-agent executions through compliance firewalls.</p>
              </div>

              <div className="chat-centered-container">
                <div className="chat-messages-scroll">
                  {chatMessages.map((msg, idx) => (
                    <div 
                      key={idx} 
                      style={{ 
                        alignSelf: msg.sender === 'user' ? 'flex-end' : 'flex-start',
                        backgroundColor: msg.sender === 'user' ? 'var(--bg-tertiary)' : 'var(--bg-primary)',
                        border: '1px solid var(--border-color)',
                        borderRadius: 'var(--radius-md)',
                        padding: '0.85rem 1rem',
                        maxWidth: '85%',
                        cursor: msg.routing ? 'pointer' : 'default'
                      }}
                      onClick={() => {
                        if (msg.routing) {
                          setSelectedRoutingMetadata(msg.routing);
                          setInspectorOpen(true);
                        }
                      }}
                    >
                      <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>
                        {msg.sender === 'user' ? 'shubham' : 'Supervisor'} • {msg.time}
                      </div>
                      
                      <p style={{ fontSize: '0.875rem', lineHeight: '1.45', whiteSpace: 'pre-wrap' }}>{msg.text}</p>
                      
                      {msg.piiSanitized && (
                        <div style={{ 
                          marginTop: '0.5rem', 
                          display: 'inline-flex', 
                          alignItems: 'center', 
                          gap: '0.3rem', 
                          fontSize: '0.7rem', 
                          color: 'var(--warning)', 
                          backgroundColor: 'rgba(125, 87, 0, 0.05)', 
                          border: '1px solid rgba(125, 87, 0, 0.15)',
                          padding: '0.25rem 0.5rem', 
                          borderRadius: '4px',
                          fontWeight: '500'
                        }}>
                          <span>🛡️ PII Firewall: Masked ({msg.sanitizedFields?.join(', ')})</span>
                        </div>
                      )}

                      {msg.routing && (
                        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.4rem' }}>
                          <span style={{ fontSize: '0.7rem', color: 'var(--text-secondary)', fontWeight: 'bold' }}>
                            [Inspect Routing Log]
                          </span>
                        </div>
                      )}
                    </div>
                  ))}
                  {chatLoading && (
                    <div style={{ alignSelf: 'flex-start', color: 'var(--text-muted)', fontSize: '0.8rem', fontStyle: 'italic', paddingLeft: '0.5rem' }}>
                      Supervisor routing payload...
                    </div>
                  )}
                </div>

                <form onSubmit={handleChatSend} className="chat-input-bar">
                  <input 
                    type="text" 
                    placeholder="Describe requirement (e.g. 'summarize document and then translate it')"
                    value={chatInput}
                    onChange={e => setChatInput(e.target.value)}
                  />
                  <button type="submit" className="btn btn-primary">Send</button>
                </form>
              </div>

              {/* Sliding Overlay Inspector Drawer */}
              <div className={`glass-drawer ${inspectorOpen ? 'open' : ''}`}>
                <div className="drawer-header">
                  <h3 style={{ fontSize: '0.95rem', fontWeight: '600' }}>Execution Inspector</h3>
                  <button className="close-btn" onClick={() => setInspectorOpen(false)}>×</button>
                </div>
                
                {selectedRoutingMetadata ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem', fontSize: '0.825rem' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span style={{ color: 'var(--text-secondary)' }}>Status:</span>
                      <span className={`status-pill ${selectedRoutingMetadata.status === 'routed' ? 'status-active' : 'status-unhealthy'}`}>
                        <span className="status-dot"></span>
                        {selectedRoutingMetadata.status}
                      </span>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Routed Agent:</span>
                      <div style={{ fontWeight: '600', marginTop: '0.2rem', color: 'var(--text-primary)' }}>{selectedRoutingMetadata.agent || 'None (No Match)'}</div>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Sequential Orchestration Plan:</span>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem', marginTop: '0.4rem' }}>
                        {selectedRoutingMetadata.executionSteps && selectedRoutingMetadata.executionSteps.length > 0 ? (
                          selectedRoutingMetadata.executionSteps.map((step, sidx) => (
                            <div key={sidx} style={{ 
                              fontSize: '0.75rem', 
                              padding: '0.4rem 0.6rem', 
                              backgroundColor: 'var(--bg-primary)', 
                              borderRadius: '4px',
                              borderLeft: '2px solid var(--text-muted)',
                              lineHeight: '1.35',
                              color: 'var(--text-secondary)'
                            }}>
                              {step}
                            </div>
                          ))
                        ) : (
                          <div style={{ color: 'var(--text-muted)', fontSize: '0.75rem' }}>No steps generated.</div>
                        )}
                      </div>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Execution Latency:</span>
                      <div style={{ fontWeight: '600', marginTop: '0.1rem', color: 'var(--text-primary)' }}>{selectedRoutingMetadata.latency} ms</div>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Mapped Arguments Payload:</span>
                      <pre style={{ 
                        marginTop: '0.4rem', 
                        fontFamily: 'var(--font-mono)', 
                        fontSize: '0.725rem', 
                        backgroundColor: 'var(--bg-primary)', 
                        padding: '0.6rem', 
                        borderRadius: 'var(--radius-md)', 
                        border: '1px solid var(--border-color)',
                        overflowX: 'auto',
                        whiteSpace: 'pre-wrap'
                      }}>
                        {JSON.stringify(selectedRoutingMetadata.params, null, 2)}
                      </pre>
                    </div>
                  </div>
                ) : (
                  <div style={{ color: 'var(--text-muted)', fontStyle: 'italic', fontSize: '0.8rem', textAlign: 'center', marginTop: '4rem' }}>
                    Select a routed message response to inspect execution payload.
                  </div>
                )}
              </div>
            </>
          )}

          {/* VIEW 4: DEMAND & GAPS */}
          {view === 'analytics' && (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2rem' }}>
                <div>
                  <h1 className="page-title premium-gradient-text">Demand Backlog</h1>
                  <p className="page-desc">Data-driven priority queue grouping unaddressed client registry requests into developer feature gaps.</p>
                </div>
                <button 
                  className="btn btn-secondary" 
                  onClick={triggerClustering}
                  disabled={clusteringLoading}
                >
                  {clusteringLoading ? 'Clustering...' : 'Run Gap Clustering'}
                </button>
              </div>

              {/* 3-Column Top KPI Summary Dashboard */}
              <div className="kpi-grid">
                <div className="kpi-card">
                  <div style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-secondary)', fontWeight: '600', letterSpacing: '0.05em' }}>Clustered Gaps</div>
                  <div className="kpi-value">{gaps.length}</div>
                </div>
                <div className="kpi-card">
                  <div style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-secondary)', fontWeight: '600', letterSpacing: '0.05em' }}>Weekly Demand (Hits)</div>
                  <div className="kpi-value">{totalUnaddressedHits}</div>
                </div>
                <div className="kpi-card">
                  <div style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-secondary)', fontWeight: '600', letterSpacing: '0.05em' }}>Avg System Latency</div>
                  <div className="kpi-value">{avgLatency} ms</div>
                </div>
              </div>

              {/* Full-width Backlog Leaderboard Table */}
              <div style={{ display: 'grid', gridTemplateColumns: '1.25fr 0.75fr', gap: '2rem', alignItems: 'start' }}>
                <div>
                  <h3 style={{ fontSize: '0.9rem', fontWeight: '600', color: 'var(--text-secondary)', marginBottom: '0.5rem' }}>Backlog Gap Leaderboard</h3>
                  <table className="premium-table">
                    <thead>
                      <tr>
                        <th>Capability Theme</th>
                        <th>Summary Details</th>
                        <th style={{ textAlign: 'center' }}>Weekly Search Hits</th>
                      </tr>
                    </thead>
                    <tbody>
                      {gaps.length === 0 ? (
                        <tr>
                          <td colSpan="3" style={{ textAlign: 'center', color: 'var(--text-muted)', fontStyle: 'italic', padding: '2rem' }}>
                            No capability gaps logged yet.
                          </td>
                        </tr>
                      ) : (
                        gaps.map(g => (
                          <tr key={g.id}>
                            <td style={{ fontWeight: '600', color: 'var(--text-primary)' }}>{g.title}</td>
                            <td style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>{g.summary}</td>
                            <td style={{ textAlign: 'center', fontWeight: '600', color: 'var(--accent-secondary)' }}>{g.countFrequency}</td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>

                {/* Telemetry log list panel */}
                <div className="premium-card" style={{ cursor: 'default', height: 'auto' }}>
                  <h3 style={{ fontSize: '0.9rem', fontWeight: '600', color: 'var(--text-primary)', marginBottom: '0.5rem' }}>Active Agent Performance</h3>
                  {telemetry.length === 0 ? (
                    <p style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>No active telemetry logs.</p>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
                      {telemetry.map((t, idx) => (
                        <div key={idx} style={{ borderBottom: '1px solid var(--border-color)', paddingBottom: '0.75rem' }}>
                          <div style={{ fontWeight: '600', marginBottom: '0.35rem', fontSize: '0.8rem', fontFamily: 'var(--font-mono)', color: 'var(--text-primary)' }}>
                            {t.agentid}
                          </div>
                          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.35rem', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                            <div>Runs: <strong>{t.countruns}</strong></div>
                            <div>Success: <strong>{Math.round(t.successrate)}%</strong></div>
                            <div>Latency: <strong>{Math.round(t.avglatency)} ms</strong></div>
                            <div>Costs: <strong>${parseFloat(t.totalcost).toFixed(4)}</strong></div>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>

            </>
          )}

          {/* VIEW 5: BILLING & TOKEN COST ALLOCATION */}
          {view === 'billing' && (
            <>
              <div style={{ marginBottom: '2rem' }}>
                <h1 className="page-title premium-gradient-text">Token Billing & Allocation</h1>
                <p className="page-desc">Corporate cost breakdown tracing LLM token expenses and resource consumption back to requesting SSO department profiles.</p>
              </div>

              {/* KPI Cards */}
              <div className="kpi-grid" style={{ marginBottom: '2.5rem' }}>
                <div className="kpi-card">
                  <div style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-secondary)', fontWeight: '600', letterSpacing: '0.05em' }}>Total Token Expenses</div>
                  <div className="kpi-value" style={{ color: 'var(--success)' }}>${totalBillingCostUsd}</div>
                </div>
                <div className="kpi-card">
                  <div style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-secondary)', fontWeight: '600', letterSpacing: '0.05em' }}>Total Queries Routed</div>
                  <div className="kpi-value">{billingAudit.length}</div>
                </div>
                <div className="kpi-card">
                  <div style={{ fontSize: '0.75rem', textTransform: 'uppercase', color: 'var(--text-secondary)', fontWeight: '600', letterSpacing: '0.05em' }}>Avg Cost per Query</div>
                  <div className="kpi-value" style={{ color: 'var(--accent)' }}>${avgBillingCostUsd}</div>
                </div>
              </div>

              {/* Grid allocation charts & audit list */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.5fr', gap: '2.5rem', alignItems: 'start' }}>
                
                {/* Left side: Department breakdown list with horizontal progress bars */}
                <div className="premium-card" style={{ padding: '2rem', cursor: 'default' }}>
                  <h3 style={{ fontSize: '1rem', fontWeight: '600', color: 'var(--text-primary)', marginBottom: '1.25rem' }}>Department Cost Distribution</h3>
                  {billingStats.length === 0 ? (
                    <p style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>No billing data generated yet.</p>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
                      {billingStats.map((stat, idx) => {
                        const totalCost = parseFloat(stat.totalcost || 0);
                        const percentage = totalBillingCost > 0 ? (totalCost / totalBillingCost) * 100 : 0;
                        let deptName = stat.department;
                        if (deptName === 'Employee') deptName = 'Standard Employee';
                        if (deptName === 'HR') deptName = 'HR Specialist';
                        if (deptName === 'Admin') deptName = 'Administrator';

                        return (
                          <div key={idx}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem', fontSize: '0.85rem' }}>
                              <span style={{ fontWeight: '600', color: 'var(--text-primary)' }}>{deptName}</span>
                              <span style={{ color: 'var(--text-secondary)', fontWeight: '600' }}>
                                ${totalCost.toFixed(5)} ({Math.round(percentage)}%)
                              </span>
                            </div>
                            {/* Horizontal Progress Bar */}
                            <div style={{ height: '8px', borderRadius: '4px', backgroundColor: 'var(--border-color)', overflow: 'hidden' }}>
                              <div style={{ 
                                height: '100%', 
                                width: `${percentage}%`, 
                                borderRadius: '4px',
                                background: idx === 0 
                                  ? 'linear-gradient(90deg, var(--accent) 0%, var(--accent-secondary) 100%)' 
                                  : idx === 1 
                                  ? 'linear-gradient(90deg, var(--success) 0%, #10b981 100%)' 
                                  : 'linear-gradient(90deg, var(--error) 0%, #ff8a80 100%)'
                              }}></div>
                            </div>
                            <div style={{ display: 'flex', gap: '1rem', marginTop: '0.35rem', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                              <span>Queries: <strong>{stat.countruns}</strong></span>
                              <span>Avg Latency: <strong>{Math.round(stat.avglatency)} ms</strong></span>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>

                {/* Right side: Detailed audit logs table */}
                <div>
                  <h3 style={{ fontSize: '1rem', fontWeight: '600', color: 'var(--text-primary)', marginBottom: '0.75rem' }}>Billing Audit Logs</h3>
                  <div style={{ maxHeight: '500px', overflowY: 'auto', border: '1px solid var(--border-color)', borderRadius: 'var(--radius-lg)', background: 'var(--bg-secondary)' }}>
                    <table className="premium-table" style={{ margin: 0, border: 'none' }}>
                      <thead style={{ position: 'sticky', top: 0, backgroundColor: 'var(--bg-secondary)', zIndex: 1, boxShadow: '0 1px 0 var(--border-color)' }}>
                        <tr>
                          <th>Query / Intent</th>
                          <th>Routing</th>
                          <th>Dept Role</th>
                          <th style={{ textAlign: 'right' }}>Cost</th>
                        </tr>
                      </thead>
                      <tbody>
                        {billingAudit.length === 0 ? (
                          <tr>
                            <td colSpan="4" style={{ textAlign: 'center', color: 'var(--text-muted)', fontStyle: 'italic', padding: '2rem' }}>
                              No query logs recorded yet. Run supervisor prompts to generate usage telemetry.
                            </td>
                          </tr>
                        ) : (
                          [...billingAudit].reverse().map(log => {
                            let deptName = log.department;
                            if (deptName === 'Employee') deptName = 'Employee';
                            if (deptName === 'HR') deptName = 'HR Spec.';
                            if (deptName === 'Admin') deptName = 'Admin';

                            let routingLabel = log.routingDecision;
                            if (routingLabel === 'chained') routingLabel = 'Chained DAG';
                            if (routingLabel === 'routed') routingLabel = 'Single Agent';
                            if (routingLabel === 'no_match') routingLabel = 'No Match';

                            return (
                              <tr key={log.id} style={{ fontSize: '0.8rem' }}>
                                <td style={{ maxWidth: '240px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: '500' }} title={log.queryText}>
                                  {log.queryText}
                                </td>
                                <td>
                                  <span style={{ 
                                    padding: '0.2rem 0.5rem', 
                                    borderRadius: 'var(--radius-sm)', 
                                    fontSize: '0.7rem', 
                                    fontWeight: '600',
                                    backgroundColor: log.routingDecision === 'chained' 
                                      ? 'rgba(130, 80, 223, 0.15)' 
                                      : log.routingDecision === 'routed' 
                                      ? 'rgba(0, 168, 204, 0.15)' 
                                      : 'rgba(255, 107, 107, 0.15)',
                                    color: log.routingDecision === 'chained' 
                                      ? 'var(--accent)' 
                                      : log.routingDecision === 'routed' 
                                      ? 'var(--accent-secondary)' 
                                      : 'var(--error)'
                                  }}>
                                    {routingLabel}
                                  </span>
                                </td>
                                <td style={{ color: 'var(--text-secondary)' }}>{deptName}</td>
                                <td style={{ textAlign: 'right', fontWeight: '600', color: 'var(--text-primary)' }}>
                                  ${parseFloat(log.tokenCost).toFixed(5)}
                                </td>
                              </tr>
                            );
                          })
                        )}
                      </tbody>
                    </table>
                  </div>
                </div>

              </div>
            </>
          )}

        </div>
      </div>
    </div>
  );
}

export default App;
