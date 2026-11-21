import React, { useCallback, useState } from 'react';
import { 
  Compass, 
  Search, 
  CheckCircle
} from 'lucide-react';
import { resolveDescriptor } from '../api';
import { SAMPLE_DESCRIPTORS } from '../data/sampleData';

export default function ResolverPlayground({ activeUser, isDemoMode }) {
  const [descriptor, setDescriptor] = useState('AMZN MKTP US*2X49L091 SEATTLE WA');
  const [amount, setAmount] = useState('1499.00');
  const [currency, setCurrency] = useState('INR');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleResolve = useCallback(async (customDesc, customAmount) => {
    const raw = customDesc || descriptor;
    if (!raw.trim()) return;

    setLoading(true);
    setError(null);

    try {
      if (!isDemoMode) {
        const res = await resolveDescriptor({
          rawDescriptor: raw,
          userId: activeUser.id,
          amount: Number(customAmount || amount || 0),
          currency: currency,
          transactionDate: new Date().toISOString()
        });
        setResult(res);
      } else {
        // Demo mode: simulate resolution tiers faithfully
        await new Promise(r => setTimeout(r, 250));
        const foundSample = SAMPLE_DESCRIPTORS.find(s => s.raw === raw);
        if (foundSample) {
          setResult({
            brandName: foundSample.expectedBrand,
            category: foundSample.category,
            gateway: raw.toUpperCase().includes('RAZORPAY') ? 'RAZORPAY' : raw.toUpperCase().includes('PAYU') ? 'PAYU' : 'DIRECT',
            confidenceScore: 0.96,
            resolutionTier: 'TIER_1',
            matchedPattern: `System-approved alias`
          });
        } else {
          // Heuristic extraction simulation
          const cleanTokens = raw.replace(/[^a-zA-Z0-9\s]/g, ' ').split(/\s+/).filter(t => t.length > 2);
          const candidate = cleanTokens[0] || 'Unknown Merchant';
          setResult({
            brandName: candidate.charAt(0).toUpperCase() + candidate.slice(1).toLowerCase(),
            category: 'Heuristic Extracted',
            gateway: 'NONE',
            confidenceScore: 0.72,
            resolutionTier: 'TIER_2',
            matchedPattern: 'HEURISTIC_TOKEN_FALLBACK'
          });
        }
      }
    } catch (err) {
      console.warn('API error in resolver:', err);
      setError('The resolver API could not be reached. Please check if the backend is running.');
      setResult(null);
    } finally {
      setLoading(false);
    }
  }, [activeUser.id, amount, currency, descriptor, isDemoMode]);

  const selectSample = (sample) => {
    setDescriptor(sample.raw);
    setAmount(String(sample.amount));
    handleResolve(sample.raw, sample.amount);
  };

  return (
    <div className="page-content animate-fade-up">
      {/* Overview Banner */}
      <div className="hero-banner">
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
          <span className="badge badge-primary">4-Tier Resolution</span>
          <span style={{ fontSize: '12px', color: 'var(--color-text-muted)' }}>
            Alias → Heuristic → Recurring → Sarvam AI (async)
          </span>
        </div>
        <h2 style={{ fontSize: '22px', fontWeight: 700, color: '#ffffff' }}>
          Merchant Descriptor Resolver Studio
        </h2>
        <p style={{ color: 'var(--color-text-secondary)', fontSize: '13px', maxWidth: '650px', marginTop: '4px' }}>
          Resolves cryptic descriptors like <code style={{ color: 'var(--color-primary-light)' }}>ACH_INB_RAZORPAY_NETF_SUB</code> into canonical merchant identities.
          Low-confidence unnamed descriptors trigger <strong>async Sarvam AI</strong> enrichment in the background.
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))', gap: '24px' }}>
        {/* Left Column: Input Form & Sample Chips */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">Live Descriptor Query</span>
            <span className="badge badge-info">Interactive</span>
          </div>
          <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div className="form-group">
              <label className="form-label">Raw Transaction Descriptor</label>
              <input
                type="text"
                className="form-control form-control-mono"
                value={descriptor}
                onChange={(e) => setDescriptor(e.target.value)}
                placeholder="e.g. NFLX*DIGITAL 499 MUMBAI IN"
              />
            </div>

            <div className="form-grid">
              <div className="form-group">
                <label className="form-label">Transaction Amount (₹)</label>
                <input
                  type="number"
                  className="form-control"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  placeholder="499.00"
                />
              </div>

              <div className="form-group">
                <label className="form-label">Currency</label>
                <input
                  type="text"
                  className="form-control"
                  value={currency}
                  onChange={(e) => setCurrency(e.target.value)}
                  disabled
                />
              </div>
            </div>

            <button
              onClick={() => handleResolve()}
              disabled={loading || !descriptor.trim()}
              className="btn btn-primary"
              style={{ width: '100%', justifyContent: 'center' }}
            >
              <Search size={16} className={loading ? 'spinner' : ''} />
              <span>{loading ? 'Resolving Identity...' : 'Resolve Descriptor'}</span>
            </button>

            {/* Quick Test Sample Chips */}
            <div style={{ marginTop: '8px' }}>
              <label className="form-label" style={{ marginBottom: '8px', display: 'block' }}>
                Instant Sample Descriptors (Click to Test)
              </label>
              <div className="chip-group">
                {SAMPLE_DESCRIPTORS.map((s, idx) => (
                  <button
                    key={idx}
                    onClick={() => selectSample(s)}
                    className="chip"
                    title={`Expects: ${s.expectedBrand} (${s.notes})`}
                  >
                    <span>{s.expectedBrand}</span>
                    <span style={{ fontSize: '10px', color: 'var(--color-text-muted)', marginLeft: '4px' }}>
                      ₹{s.amount}
                    </span>
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>

        {/* Right Column: Resolution Result */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">Resolution Output</span>
            {result && (
              <span className={`badge ${
                result.resolutionTier === 'TIER_1' ? 'badge-success' : 
                result.resolutionTier === 'TIER_2' ? 'badge-warning' : 'badge-danger'
              }`}>
                {result.resolutionTier}
              </span>
            )}
          </div>
          <div className="card-body">
            {error && <div className="alert alert-error" role="alert">{error}</div>}
            {loading ? (
              <div className="loading-center">
                <div className="spinner"></div>
                <p>Running resolution pipeline...</p>
              </div>
            ) : result ? (
              <div className="resolve-result">
                <div>
                  <span style={{ fontSize: '11px', color: 'var(--color-text-muted)', textTransform: 'uppercase', letterSpacing: '0.6px' }}>
                    Resolved Merchant Identity
                  </span>
                  <div className="resolve-brand">{result.brandName}</div>
                </div>

                <div className="resolve-meta">
                  <span className="badge badge-primary">{result.category || 'General'}</span>
                  {result.gateway && result.gateway !== 'NONE' && (
                    <span className="badge badge-info">Gateway: {result.gateway}</span>
                  )}
                  <span className="badge badge-muted">Tier: {result.resolutionTier}</span>
                </div>

                {result.resolutionTier === 'TIER_2' && result.matchedPattern === 'RECURRING_PATTERN_EXTRACT' && (
                  <div className="alert alert-warning" style={{ marginTop: '8px', fontSize: '12px' }}>
                    ⚡ Unnamed recurring descriptor detected. Sarvam AI enrichment scheduled in background.
                  </div>
                )}

                {result.confidenceScore < 0.7 && result.resolutionTier !== 'TIER_1' && (
                  <div className="alert alert-warning" style={{ marginTop: '8px', fontSize: '12px' }}>
                    🔍 Low confidence — Sarvam AI async enrichment queued for this descriptor.
                  </div>
                )}

                <div className="divider"></div>

                {/* Confidence Bar */}
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', marginBottom: '6px' }}>
                    <span style={{ color: 'var(--color-text-secondary)' }}>Confidence Score</span>
                    <strong style={{ color: result.confidenceScore > 0.85 ? 'var(--color-success)' : 'var(--color-warning)' }}>
                      {(result.confidenceScore * 100).toFixed(1)}%
                    </strong>
                  </div>
                  <div className="confidence-bar-wrap" style={{ width: '100%' }}>
                    <div
                      className="confidence-bar-fill"
                      style={{
                        width: `${Math.min(100, Math.max(10, result.confidenceScore * 100))}%`,
                        background: result.confidenceScore > 0.85 
                          ? 'linear-gradient(90deg, #10b981, #06b6d4)' 
                          : 'linear-gradient(90deg, #f59e0b, #ef4444)'
                      }}
                    ></div>
                  </div>
                </div>

                {/* Matched Pattern details */}
                <div style={{ marginTop: '8px' }}>
                  <span style={{ fontSize: '11px', color: 'var(--color-text-muted)', display: 'block', marginBottom: '4px' }}>
                    Resolution Method
                  </span>
                  <div style={{
                    background: 'var(--color-bg-base)',
                    padding: '8px 12px',
                    borderRadius: 'var(--radius-sm)',
                    fontFamily: 'var(--font-mono)',
                    fontSize: '12px',
                    color: 'var(--color-accent)',
                    wordBreak: 'break-all'
                  }}>
                    {result.matchedPattern || 'Heuristic fallback — Sarvam AI async enrichment pending'}
                  </div>
                </div>
              </div>
            ) : (
              <div className="empty-state">
                <Compass size={32} color="var(--color-text-muted)" />
                <p>Enter a descriptor or click a sample chip above to see resolution.</p>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Resolution Tier Legend */}
      <div className="card" style={{ marginTop: '0' }}>
        <div className="card-header">
          <span className="card-title">Resolution Architecture</span>
          <span className="badge badge-muted">Reference</span>
        </div>
        <div className="card-body">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px' }}>
            {[
              { tier: 'Tier 1', label: 'System-Approved Aliases', desc: 'Admin-seeded merchant aliases from MongoDB. High confidence (≥98%). Never auto-learned.', color: 'var(--color-success)' },
              { tier: 'Tier 2', label: 'Heuristic / Vector', desc: 'Regex tokens, alias map, Levenshtein fuzzy match, Pinecone vector similarity. Returned only if confidence ≥80%.', color: 'var(--color-warning)' },
              { tier: 'Tier 2.5', label: 'Unnamed Recurring', desc: 'If the same unknown descriptor recurs ≥2× for the user, its stable token becomes a placeholder group name.', color: '#a78bfa' },
              { tier: 'Tier 3', label: 'Sarvam AI (Async)', desc: 'Triggered only for suspicious / unnamed low-confidence descriptors. Runs in background. Never synchronous.', color: 'var(--color-danger)' },
            ].map(item => (
              <div key={item.tier} style={{ padding: '12px', background: 'var(--color-bg-base)', borderRadius: 'var(--radius-sm)', borderLeft: `3px solid ${item.color}` }}>
                <div style={{ fontWeight: 700, color: item.color, fontSize: '12px' }}>{item.tier}</div>
                <div style={{ fontWeight: 600, fontSize: '13px', marginTop: '2px' }}>{item.label}</div>
                <div style={{ fontSize: '12px', color: 'var(--color-text-muted)', marginTop: '4px' }}>{item.desc}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
