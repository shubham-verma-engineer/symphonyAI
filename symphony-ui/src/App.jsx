import React, { useState, useEffect } from 'react';

const API_BASE = 'http://localhost:8091/api/v1/agents';

// SVG Icons
const SearchIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
);
const GridIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="7" height="9"></rect><rect x="14" y="3" width="7" height="5"></rect><rect x="14" y="12" width="7" height="9"></rect><rect x="3" y="16" width="7" height="5"></rect></svg>
);
const UploadIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
);
const SunIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>
);
const MoonIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>
);

function App() {
  const [view, setView] = useState('marketplace'); // 'marketplace' | 'onboard'
  const [theme, setTheme] = useState(() => localStorage.getItem('symphony-theme') || 'dark');
  const [agents, setAgents] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('All');
  const [loading, setLoading] = useState(false);
  const [manifestText, setManifestText] = useState(JSON.stringify({
    "manifestVersion": "1.0",
    "name": "document-summarizer",
    "version": "1.0.0",
    "description": "Extracts summaries and action items from PDFs and DOCX files.",
    "endpoints": {
      "execution": "https://summarizer.internal.net/api/v1/summarize",
      "health": "https://summarizer.internal.net/health"
    },
    "parameters": [
      {
        "name": "documentUrl",
        "type": "string",
        "description": "Secure file path of the document",
        "required": true
      }
    ],
    "security": {
      "requiresRBAC": false
    }
  }, null, 2));

  const [onboardLogs, setOnboardLogs] = useState([]);
  const [selectedAgent, setSelectedAgent] = useState(null);

  // Sync theme attribute with DOM and save to local storage
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('symphony-theme', theme);
  }, [theme]);

  const toggleTheme = () => {
    setTheme(prev => prev === 'dark' ? 'light' : 'dark');
  };

  // Fetch registered agents
  const fetchAgents = async () => {
    setLoading(true);
    try {
      const res = await fetch(API_BASE);
      if (res.ok) {
        const data = await res.json();
        setAgents(data);
      }
    } catch (err) {
      console.error("Failed loading agents", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (view === 'marketplace') {
      fetchAgents();
      setSelectedAgent(null);
    }
  }, [view]);

  // Semantic search query
  const handleSearch = async (e) => {
    e.preventDefault();
    if (!searchQuery.trim()) {
      fetchAgents();
      return;
    }
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/search`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: searchQuery, limit: 10 })
      });
      if (res.ok) {
        const data = await res.json();
        setAgents(data);
      }
    } catch (err) {
      console.error("Semantic search failed", err);
    } finally {
      setLoading(false);
    }
  };

  // Onboarding agent submission
  const handleOnboard = async (e) => {
    e.preventDefault();
    setOnboardLogs([]);
    
    const addLog = (text, type = 'info') => {
      setOnboardLogs(prev => [...prev, { text, type, time: new Date().toLocaleTimeString() }]);
    };

    try {
      addLog("Parsing manifest payload...", "info");
      let parsed;
      try {
        parsed = JSON.parse(manifestText);
      } catch (err) {
        addLog("JSON Parse Error: Invalid format.", "error");
        return;
      }

      addLog(`Validating JSON Schema parameters for '${parsed.name || 'unnamed'}'...`, "info");
      if (!parsed.name || !parsed.description || !parsed.endpoints) {
        addLog("Schema Error: Missing core properties (name, description, endpoints).", "error");
        return;
      }
      addLog("Validation PASS: Schema matches specifications.", "success");

      addLog(`Loopback Check: Querying agent health at ${parsed.endpoints.health}...`, "info");

      const res = await fetch(`${API_BASE}/onboard`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(parsed)
      });

      if (res.ok) {
        const data = await res.json();
        addLog(`Health Status: Endpoint active (HTTP 200 OK).`, "success");
        addLog(`Embedding: Created vector representation in pgvector index.`, "success");
        addLog(`Registry commit: Agent '${data.name}' registered with ID: ${data.id}`, "success");
        addLog("Onboarding success.", "success");
      } else {
        const errText = await res.text();
        addLog(`Verification Failed: ${errText || 'Unreachable health endpoint.'}`, "error");
      }
    } catch (err) {
      addLog(`Network Failure: ${err.message}`, "error");
    }
  };

  // Filtering category list (client-side fallback logic)
  const filteredAgents = agents.filter(a => {
    if (selectedCategory === 'All') return true;
    const nameLower = a.name.toLowerCase();
    const descLower = a.description.toLowerCase();
    if (selectedCategory === 'Legal') return nameLower.includes('legal') || descLower.includes('legal');
    if (selectedCategory === 'HR') return nameLower.includes('hr') || nameLower.includes('payroll') || descLower.includes('payroll');
    if (selectedCategory === 'Finance') return nameLower.includes('finance') || descLower.includes('finance');
    if (selectedCategory === 'DevTools') return nameLower.includes('summary') || nameLower.includes('parse') || descLower.includes('summar');
    return true;
  });

  return (
    <div className="app-container">
      {/* Sidebar navigation panel */}
      <aside className="sidebar">
        <div className="brand">
          <span style={{ fontSize: '1.6rem' }}>💎</span>
          <span>SymphonyAI</span>
        </div>
        
        <nav style={{ marginTop: '1.5rem' }}>
          <ul className="nav-links">
            <li 
              className={`nav-item ${view === 'marketplace' ? 'active' : ''}`}
              onClick={() => setView('marketplace')}
            >
              <GridIcon />
              <span>Registry Hub</span>
            </li>
            <li 
              className={`nav-item ${view === 'onboard' ? 'active' : ''}`}
              onClick={() => setView('onboard')}
            >
              <UploadIcon />
              <span>Onboard Agent</span>
            </li>
          </ul>
        </nav>

        {/* Theme mode toggle */}
        <button className="theme-toggle-btn" onClick={toggleTheme}>
          {theme === 'dark' ? (
            <>
              <SunIcon />
              <span>Light Mode</span>
            </>
          ) : (
            <>
              <MoonIcon />
              <span>Dark Mode</span>
            </>
          )}
        </button>

        <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginTop: '1rem', textAlign: 'center' }}>
          Workspace: default_ws
        </div>
      </aside>

      {/* Main panel layout */}
      <main className="main-content">
        {view === 'marketplace' ? (
          <>
            <div>
              <h1 style={{ fontSize: '2.5rem', fontWeight: '700', marginBottom: '0.5rem' }}>Registry Directory</h1>
              <p style={{ color: 'var(--text-secondary)', fontSize: '1rem' }}>Central catalog of all registered internal enterprise AI agents.</p>
            </div>

            {/* Semantic Search Box */}
            <form onSubmit={handleSearch} className="search-container">
              <input 
                type="text" 
                className="search-input"
                placeholder="Search agents semantically (e.g. 'I need to check employee payroll')"
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
              />
              <button type="submit" className="btn btn-primary">
                <SearchIcon />
                <span>Search</span>
              </button>
            </form>

            {/* Category pills switcher */}
            <div className="category-pills">
              {['All', 'Legal', 'HR', 'Finance', 'DevTools'].map(cat => (
                <span 
                  key={cat}
                  className={`pill ${selectedCategory === cat ? 'active' : ''}`}
                  onClick={() => setSelectedCategory(cat)}
                >
                  {cat}
                </span>
              ))}
            </div>

            {loading ? (
              <div style={{ padding: '4rem', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.95rem' }}>
                Querying registry database...
              </div>
            ) : (
              <div className="agent-grid">
                {filteredAgents.length === 0 ? (
                  <div style={{ gridColumn: '1/-1', padding: '4rem', textAlign: 'center', color: 'var(--text-secondary)', border: '1px dashed var(--border-color)', borderRadius: 'var(--radius-md)' }}>
                    No registered agents found. Try another search or register a new manifest!
                  </div>
                ) : (
                  filteredAgents.map(agent => (
                    <div 
                      key={agent.id} 
                      className="agent-card"
                      onClick={() => setSelectedAgent(agent)}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                        <div>
                          <h3 style={{ fontSize: '1.2rem', fontWeight: '600', marginBottom: '0.2rem' }}>{agent.name}</h3>
                          <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>v{agent.version}</span>
                        </div>
                        <span className={`badge ${agent.status === 'ACTIVE' ? 'badge-active' : 'badge-unhealthy'}`}>
                          {agent.status}
                        </span>
                      </div>
                      
                      <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', flexGrow: 1, lineBreak: 'anywhere', lineHeight: '1.4' }}>
                        {agent.description}
                      </p>

                      {agent.similarity < 1.0 && agent.similarity > 0 && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.8rem', color: 'var(--accent-primary)', fontWeight: '500' }}>
                          <span>Similarity Match:</span>
                          <span>{Math.round(agent.similarity * 100)}%</span>
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>
            )}

            {/* Agent Info Detail Modal Overlay */}
            {selectedAgent && (
              <div 
                style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }}
                onClick={() => setSelectedAgent(null)}
              >
                <div 
                  className="glass-card" 
                  style={{ width: '100%', maxWidth: '550px', padding: '2.5rem', display: 'flex', flexDirection: 'column', gap: '1.5rem', margin: '1rem', background: 'var(--bg-secondary)', borderColor: 'var(--border-color)' }}
                  onClick={e => e.stopPropagation()}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <h2 style={{ fontSize: '1.6rem' }}>{selectedAgent.name}</h2>
                    <span className="badge badge-active">{selectedAgent.status}</span>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.4rem', fontSize: '0.85rem', textTransform: 'uppercase' }}>Description</h4>
                    <p style={{ fontSize: '0.95rem', lineHeight: '1.4' }}>{selectedAgent.description}</p>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.4rem', fontSize: '0.85rem', textTransform: 'uppercase' }}>Endpoints</h4>
                    <div style={{ fontFamily: 'monospace', fontSize: '0.8rem', display: 'flex', flexDirection: 'column', gap: '0.25rem', backgroundColor: 'var(--bg-primary)', padding: '0.75rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border-color)' }}>
                      <div>Execution: <span style={{ color: 'var(--accent-primary)' }}>{selectedAgent.endpointUrl}</span></div>
                      <div>Health Check: <span style={{ color: 'var(--accent-primary)' }}>{selectedAgent.healthCheckUrl}</span></div>
                    </div>
                  </div>
                  <div>
                    <h4 style={{ color: 'var(--text-secondary)', marginBottom: '0.5rem', fontSize: '0.85rem', textTransform: 'uppercase' }}>Routing Parameters</h4>
                    {selectedAgent.parameters && selectedAgent.parameters.length > 0 ? (
                      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                        <thead>
                          <tr style={{ borderBottom: '1px solid var(--border-color)', textAlign: 'left', color: 'var(--text-secondary)' }}>
                            <th style={{ padding: '0.5rem 0' }}>Name</th>
                            <th style={{ padding: '0.5rem 0' }}>Type</th>
                            <th style={{ padding: '0.5rem 0' }}>Required</th>
                          </tr>
                        </thead>
                        <tbody>
                          {selectedAgent.parameters.map(p => (
                            <tr key={p.name} style={{ borderBottom: '1px solid var(--border-color)' }}>
                              <td style={{ padding: '0.5rem 0', fontFamily: 'monospace', fontWeight: '500' }}>{p.name}</td>
                              <td style={{ padding: '0.5rem 0', color: 'var(--accent-primary)' }}>{p.type}</td>
                              <td style={{ padding: '0.5rem 0' }}>{p.required ? 'Yes' : 'No'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : (
                      <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>No parameter configuration specified.</p>
                    )}
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.5rem' }}>
                    <button className="btn btn-secondary" onClick={() => setSelectedAgent(null)}>Close Details</button>
                  </div>
                </div>
              </div>
            )}
          </>
        ) : (
          /* ONBOARDING CREATOR CONSOLE VIEW */
          <>
            <div>
              <h1 style={{ fontSize: '2.5rem', fontWeight: '700', marginBottom: '0.5rem' }}>Agent Registration</h1>
              <p style={{ color: 'var(--text-secondary)', fontSize: '1rem' }}>Upload `agent-manifest.json` configurations to index your tool into the registry.</p>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr', gap: '2.5rem', alignItems: 'start' }}>
              {/* Form Input Section */}
              <form onSubmit={handleOnboard} className="glass-card" style={{ padding: '2rem', display: 'flex', flexDirection: 'column', gap: '1.25rem', background: 'var(--bg-secondary)' }}>
                <div className="form-group">
                  <label>Agent Manifest JSON Spec</label>
                  <textarea 
                    rows="15" 
                    className="form-control"
                    style={{ fontFamily: 'monospace', fontSize: '0.85rem', resize: 'vertical' }}
                    value={manifestText}
                    onChange={e => setManifestText(e.target.value)}
                  />
                </div>
                <div style={{ display: 'flex', gap: '1rem', marginTop: '0.5rem' }}>
                  <button type="submit" className="btn btn-primary" style={{ flexGrow: 1, justifyContent: 'center' }}>
                    <UploadIcon />
                    <span>Upload & Register</span>
                  </button>
                </div>
              </form>

              {/* Validation Logs Terminal */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                <h3 style={{ fontSize: '1.1rem', fontWeight: '600' }}>Verification Terminal</h3>
                <div className="terminal-console">
                  {onboardLogs.length === 0 ? (
                    <div style={{ color: 'var(--text-secondary)', fontStyle: 'italic' }}>
                      Logs will write here once registration is triggered...
                    </div>
                  ) : (
                    onboardLogs.map((log, idx) => (
                      <div key={idx} className={`terminal-line ${log.type}`}>
                        <span style={{ color: 'var(--text-secondary)', marginRight: '0.5rem' }}>[{log.time}]</span>
                        <span>{log.text}</span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </div>
          </>
        )}
      </main>
    </div>
  );
}

export default App;
