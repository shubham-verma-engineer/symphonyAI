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
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>
);
const MoonIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>
);
const LogoutIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
);

function App() {
  const [view, setView] = useState('marketplace'); // 'marketplace' | 'onboard' | 'chat' | 'analytics'
  const [theme, setTheme] = useState(() => localStorage.getItem('symphony-theme') || 'dark');
  const [userRole, setUserRole] = useState(() => localStorage.getItem('symphony-role') || 'Employee');
  
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
      "authorizedRoles": ["Admin", "HR"]
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
  const [chatLoading, setChatLoading] = useState(false);

  // Gaps & Telemetry state
  const [gaps, setGaps] = useState([]);
  const [telemetry, setTelemetry] = useState([]);
  const [clusteringLoading, setClusteringLoading] = useState(false);

  // Sync theme
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('symphony-theme', theme);
  }, [theme]);

  // Sync userRole
  useEffect(() => {
    localStorage.setItem('symphony-role', userRole);
  }, [userRole]);

  const toggleTheme = () => {
    setTheme(prev => prev === 'dark' ? 'light' : 'dark');
  };

  // Fetch agents
  const fetchAgents = async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/agents`);
      if (res.ok) {
        setAgents(await res.json());
      }
    } catch (err) {
      console.error("Failed loading agents", err);
    } finally {
      setLoading(false);
    }
  };

  // Fetch gaps and telemetry
  const fetchAnalytics = async () => {
    try {
      const resGaps = await fetch(`${API_BASE}/admin/gaps`);
      if (resGaps.ok) setGaps(await resGaps.json());

      const resTel = await fetch(`${API_BASE}/admin/telemetry`);
      if (resTel.ok) setTelemetry(await resTel.json());
    } catch (err) {
      console.error("Failed loading analytics", err);
    }
  };

  useEffect(() => {
    if (view === 'marketplace') {
      fetchAgents();
      setSelectedAgent(null);
    } else if (view === 'analytics') {
      fetchAnalytics();
    }
  }, [view]);

  // Search agents
  const handleSearch = async (e) => {
    e.preventDefault();
    if (!searchQuery.trim()) {
      fetchAgents();
      return;
    }
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/agents/search`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
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
      const res = await fetch(`${API_BASE}/agents/onboard`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
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
      const res = await fetch(`${API_BASE}/supervisor/chat`, {
        method: 'POST',
        headers: { 
          'Content-Type': 'application/json',
          'X-User-Role': userRole
        },
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
      const res = await fetch(`${API_BASE}/admin/gaps/trigger-cluster`, { method: 'POST' });
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

  return (
    <div>
      {/* Premium Top Navigation Header */}
      <header className="top-header">
        <div className="brand-section">
          <div className="logo-badge">S</div>
          <div className="brand-titles">
            <span className="brand-name">Symphony AI</span>
            <span className="brand-subtext">Enterprise agent directory · supervisor orchestrator</span>
          </div>
        </div>

        {/* Navigation Tabs */}
        <nav>
          <ul className="nav-tabs">
            <li className={`nav-tab ${view === 'marketplace' ? 'active' : ''}`} onClick={() => setView('marketplace')}>
              <span>Registry Hub</span>
            </li>
            <li className={`nav-tab ${view === 'onboard' ? 'active' : ''}`} onClick={() => setView('onboard')}>
              <span>Onboard Agent</span>
            </li>
            <li className={`nav-tab ${view === 'chat' ? 'active' : ''}`} onClick={() => setView('chat')}>
              <span>Supervisor Chat</span>
            </li>
            <li className={`nav-tab ${view === 'analytics' ? 'active' : ''}`} onClick={() => setView('analytics')}>
              <span>Demand & Gaps</span>
            </li>
          </ul>
        </nav>

        {/* Action Controls */}
        <div className="header-actions">
          {/* Workspace badge */}
          <div className="workspace-pill">
            <span style={{ width: '8px', height: '8px', borderRadius: '50%', backgroundColor: '#10b981' }}></span>
            <span>default_ws</span>
          </div>

          {/* User Role Selector */}
          <div className="role-selector-wrapper">
            <select value={userRole} onChange={e => setUserRole(e.target.value)}>
              <option value="Employee">Employee (Standard)</option>
              <option value="HR">HR Specialist</option>
              <option value="Admin">Administrator</option>
            </select>
          </div>

          {/* Theme switcher toggle */}
          <button className="icon-btn" onClick={toggleTheme} title="Toggle theme">
            {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
          </button>

          <span style={{ fontSize: '0.85rem', fontWeight: '500', color: 'var(--text-secondary)' }}>shubham</span>
          <button className="icon-btn" title="Sign out">
            <LogoutIcon />
          </button>
        </div>
      </header>

      {/* Main Panel Content Wrapper */}
      <main className="main-wrapper">
        
        {/* VIEW 1: REGISTRY HUB */}
        {view === 'marketplace' && (
          <>
            <div className="section-header">
              <h1 className="section-title">Internal Registry</h1>
              <p className="section-desc">Search, filter, and discover all employee-built AI workflows registered inside your enterprise workspace.</p>
            </div>

            <div style={{ display: 'flex', gap: '1rem', alignItems: 'center', marginBottom: '1.5rem' }}>
              <form onSubmit={handleSearch} className="search-container" style={{ maxWidth: '500px', flexGrow: 1 }}>
                <input 
                  type="text" 
                  className="search-input"
                  placeholder="Search agents semantically (e.g. 'translate legal contracts')"
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                />
                <button type="submit" className="btn btn-primary" style={{ height: '100%' }}><SearchIcon /></button>
              </form>

              <div className="category-pills">
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
                        <div>
                          <h3 style={{ fontSize: '1.05rem', fontWeight: '600', color: 'var(--text-primary)' }}>{agent.name}</h3>
                          <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>v{agent.version}</span>
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '0.3rem' }}>
                          <span className={`status-pill ${agent.status === 'ACTIVE' ? 'status-active' : 'status-unhealthy'}`}>
                            <span className="status-dot"></span>
                            {agent.status}
                          </span>
                          {agent.requiresRbac && (
                            <span className="badge" style={{ backgroundColor: 'rgba(239, 68, 68, 0.08)', color: '#ef4444', border: '1px solid rgba(239, 68, 68, 0.15)', fontSize: '0.65rem' }}>
                              RBAC
                            </span>
                          )}
                        </div>
                      </div>
                      <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', flexGrow: 1, lineBreak: 'anywhere', lineHeight: '1.4' }}>{agent.description}</p>
                      {agent.similarity < 1.0 && agent.similarity > 0 && (
                        <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', fontWeight: '500' }}>
                          Match Score: <span style={{ color: 'var(--text-primary)' }}>{Math.round(agent.similarity * 100)}%</span>
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>
            )}

            {/* Agent Detail Modal */}
            {selectedAgent && (
              <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }} onClick={() => setSelectedAgent(null)}>
                <div className="premium-card" style={{ width: '100%', maxWidth: '520px', padding: '2rem', display: 'flex', flexDirection: 'column', gap: '1.25rem', margin: '1rem', background: 'var(--bg-secondary)', borderColor: 'var(--border-color)', cursor: 'default' }} onClick={e => e.stopPropagation()}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <h2 style={{ fontSize: '1.4rem', fontWeight: '600' }}>{selectedAgent.name}</h2>
                    <span className="status-pill status-active"><span className="status-dot"></span>{selectedAgent.status}</span>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.3rem', fontSize: '0.8rem', textTransform: 'uppercase', fontWeight: '600' }}>Description</h4>
                    <p style={{ fontSize: '0.9rem', lineHeight: '1.4' }}>{selectedAgent.description}</p>
                  </div>
                  {selectedAgent.requiresRbac && (
                    <div>
                      <h4 style={{ color: '#ef4444', marginBottom: '0.3rem', fontSize: '0.8rem', textTransform: 'uppercase', fontWeight: '600' }}>Governance Rules</h4>
                      <p style={{ fontSize: '0.85rem' }}>
                        Execution restricted to roles: 
                        <strong style={{ marginLeft: '0.4rem' }}>{selectedAgent.authorizedRoles?.join(', ') || 'None'}</strong>
                      </p>
                    </div>
                  )}
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.3rem', fontSize: '0.8rem', textTransform: 'uppercase', fontWeight: '600' }}>Endpoints</h4>
                    <div style={{ fontFamily: 'monospace', fontSize: '0.8rem', display: 'flex', flexDirection: 'column', gap: '0.25rem', backgroundColor: 'var(--bg-primary)', padding: '0.6rem 0.8rem', borderRadius: 'var(--radius-md)', border: '1px solid var(--border-color)' }}>
                      <div>Execution: <span style={{ color: 'var(--text-muted)' }}>{selectedAgent.endpointUrl}</span></div>
                      <div>Health: <span style={{ color: 'var(--text-muted)' }}>{selectedAgent.healthCheckUrl}</span></div>
                    </div>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.4rem', fontSize: '0.8rem', textTransform: 'uppercase', fontWeight: '600' }}>Parameters Schema</h4>
                    {selectedAgent.parameters && selectedAgent.parameters.length > 0 ? (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem' }}>
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
                      <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>No parameter configuration specified.</p>
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
              <h1 className="section-title">Onboard Agent</h1>
              <p className="section-desc">Drop manifest schemas in. Registry pings endpoints, validates JSON models, and indexes descriptions semantically.</p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr', gap: '2rem', alignItems: 'start' }}>
              <div className="ingest-drop-zone" style={{ width: '100%' }}>
                <div style={{ width: '100%', textAlign: 'left' }}>
                  <label style={{ fontSize: '0.8rem', fontWeight: '600', color: 'var(--text-secondary)', display: 'block', marginBottom: '0.5rem' }}>agent-manifest.json Spec</label>
                  <textarea 
                    rows="14" 
                    className="form-control"
                    style={{ fontFamily: 'monospace', fontSize: '0.8rem', resize: 'vertical', width: '100%', backgroundColor: 'var(--bg-secondary)' }}
                    value={manifestText}
                    onChange={e => setManifestText(e.target.value)}
                  />
                </div>
                <button onClick={handleOnboard} className="btn btn-primary" style={{ alignSelf: 'flex-start' }}>
                  <UploadIcon />
                  <span>Register Manifest</span>
                </button>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                <h3 style={{ fontSize: '0.95rem', fontWeight: '600', color: 'var(--text-secondary)' }}>Verification Logs</h3>
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
              <h1 className="section-title">Supervisor Chat</h1>
              <p className="section-desc">State compound tasks or single requirements. Supervisor routes pipelines, checks RBAC roles, and fires compliance monitors.</p>
            </div>

            <div className="chat-window">
              <div className="chat-bubble-container">
                <div style={{ flexGrow: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '1rem', paddingRight: '0.5rem' }}>
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
                      onClick={() => msg.routing && setSelectedRoutingMetadata(msg.routing)}
                    >
                      <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginBottom: '0.2rem' }}>
                        {msg.sender === 'user' ? 'shubham' : 'Supervisor'} • {msg.time}
                      </div>
                      
                      <p style={{ fontSize: '0.9rem', lineHeight: '1.4', whiteSpace: 'pre-wrap' }}>{msg.text}</p>
                      
                      {msg.piiSanitized && (
                        <div style={{ 
                          marginTop: '0.5rem', 
                          display: 'inline-flex', 
                          alignItems: 'center', 
                          gap: '0.3rem', 
                          fontSize: '0.7rem', 
                          color: 'var(--warning)', 
                          backgroundColor: 'rgba(245, 158, 11, 0.05)', 
                          border: '1px solid rgba(245, 158, 11, 0.2)',
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
                            [Inspect Routing Logs]
                          </span>
                        </div>
                      )}
                    </div>
                  ))}
                  {chatLoading && (
                    <div style={{ alignSelf: 'flex-start', color: 'var(--text-muted)', fontSize: '0.85rem', fontStyle: 'italic' }}>
                      Supervisor routing intent...
                    </div>
                  )}
                </div>

                <form onSubmit={handleChatSend} className="chat-input-row">
                  <input 
                    type="text" 
                    placeholder="Describe requirement (e.g. 'extract document text and then translate it')"
                    value={chatInput}
                    onChange={e => setChatInput(e.target.value)}
                  />
                  <button type="submit" className="btn btn-primary">Send</button>
                </form>
              </div>

              {/* Execution Inspector Side Panel */}
              <div className="chat-bubble-container" style={{ overflowY: 'auto' }}>
                <h3 style={{ fontSize: '1rem', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.5rem', fontWeight: '600' }}>Execution Inspector</h3>
                
                {selectedRoutingMetadata ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', fontSize: '0.85rem' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span>Status:</span>
                      <span className={`status-pill ${selectedRoutingMetadata.status === 'routed' ? 'status-active' : 'status-unhealthy'}`}>
                        <span className="status-dot"></span>
                        {selectedRoutingMetadata.status}
                      </span>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Routed Agent:</span>
                      <div style={{ fontWeight: '600', marginTop: '0.2rem' }}>{selectedRoutingMetadata.agent || 'None (No Match)'}</div>
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
                              lineHeight: '1.3',
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
                      <div style={{ fontWeight: '500', marginTop: '0.1rem' }}>{selectedRoutingMetadata.latency} ms</div>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-secondary)' }}>Mapped Arguments Payload:</span>
                      <pre style={{ 
                        marginTop: '0.4rem', 
                        fontFamily: 'monospace', 
                        fontSize: '0.75rem', 
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
                  <div style={{ color: 'var(--text-muted)', fontStyle: 'italic', fontSize: '0.85rem', textAlign: 'center', marginTop: '4rem' }}>
                    Select a routed message response to inspect execution payload.
                  </div>
                )}
              </div>
            </div>
          </>
        )}

        {/* VIEW 4: DEMAND & GAPS */}
        {view === 'analytics' && (
          <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2rem' }}>
              <div>
                <h1 className="section-title">Demand Backlog</h1>
                <p className="section-desc">Continuous feedback mapping: clustered innovation backlog topics built from unaddressed client registry requests.</p>
              </div>
              <button 
                className="btn btn-secondary" 
                onClick={triggerClustering}
                disabled={clusteringLoading}
              >
                {clusteringLoading ? 'Clustering...' : 'Run Gap Clustering'}
              </button>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1.25fr 0.75fr', gap: '2rem', alignItems: 'start' }}>
              
              {/* Leaderboard Table (matches CodeLens Ingested structure) */}
              <div>
                <h3 style={{ fontSize: '1rem', fontWeight: '600', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Backlog Gap Leaderboard</h3>
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
              <div className="premium-card" style={{ cursor: 'default' }}>
                <h3 style={{ fontSize: '1rem', fontWeight: '600', color: 'var(--text-primary)' }}>Execution Telemetry</h3>
                {telemetry.length === 0 ? (
                  <p style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>No execution logs recorded.</p>
                ) : (
                  <div style={{ display: 'flex', flexString: 'column', flexDirection: 'column', gap: '1rem' }}>
                    {telemetry.map((t, idx) => (
                      <div key={idx} style={{ borderBottom: '1px solid var(--border-color)', paddingBottom: '0.85rem' }}>
                        <div style={{ fontWeight: '600', marginBottom: '0.4rem', fontSize: '0.85rem', fontFamily: 'monospace', color: 'var(--text-primary)' }}>
                          {t.agentid}
                        </div>
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.4rem', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
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

      </main>
    </div>
  );
}

export default App;
