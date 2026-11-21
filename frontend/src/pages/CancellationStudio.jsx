import React, { useCallback, useState, useEffect } from 'react';
import { 
  FileText, 
  Copy, 
  Check, 
  Download, 
  Printer, 
  BookOpen,
  Mail,
  Send,
  ShieldCheck,
  ExternalLink
} from 'lucide-react';
import { getPlaybook, generateLetter, sendDirectMail } from '../api';
import { SAMPLE_REVOCATION_PRESETS } from '../data/sampleData';

const BANK_DESK_EMAILS = {
  'hdfc': 'nodal.officer@hdfcbank.com',
  'icici': 'headservicequality@icicibank.com',
  'sbi': 'customercare@sbi.co.in',
  'axis': 'nodal.officer@axisbank.com',
  'kotak': 'nodalofficer@kotak.com',
  'pnb': 'care@pnb.co.in',
  'paytm': 'care@paytm.com',
  'netflix': 'grievance-india@netflix.com',
  'spotify': 'support@spotify.com',
  'amazon': 'grievance-officer@amazon.in',
  'hotstar': 'disneyplus.in@hotstar.com',
  'swiggy': 'support@swiggy.in',
  'zomato': 'order@zomato.com'
};

function guessDeskEmail(bankName, merchantName) {
  const b = (bankName || '').toLowerCase();
  const m = (merchantName || '').toLowerCase();
  for (const [key, email] of Object.entries(BANK_DESK_EMAILS)) {
    if (b.includes(key) || m.includes(key)) return email;
  }
  return 'nodal.officer@' + (bankName || 'bank').toLowerCase().replace(/[^a-z0-9]/g, '') + '.com';
}

export default function CancellationStudio({ activeUser, isDemoMode, initialData }) {
  // Playbook state
  const [selectedRail, setSelectedRail] = useState(initialData?.rail || 'UPI_AUTOPAY');
  const [playbookContent, setPlaybookContent] = useState('');

  // Notice generator form state
  const [userName, setUserName] = useState(initialData?.userName || activeUser?.name || 'Account Holder');
  const [bankName, setBankName] = useState(initialData?.bankName || 'HDFC Bank');
  const [merchantName, setMerchantName] = useState(initialData?.merchantName || 'Adobe Creative Cloud');
  const [mandateRef, setMandateRef] = useState(initialData?.mandateRef || 'MND-HDFC-99281-ADOBE');
  const [recipientEmail, setRecipientEmail] = useState(() => guessDeskEmail('HDFC Bank', 'Adobe Creative Cloud'));
  
  const [generatedLetter, setGeneratedLetter] = useState('');
  const [directMailData, setDirectMailData] = useState(null);
  const [letterError, setLetterError] = useState('');
  const [generating, setGenerating] = useState(false);
  const [sendingMail, setSendingMail] = useState(false);
  const [mailSentNotice, setMailSentNotice] = useState('');
  const [copied, setCopied] = useState(false);

  // Update recipient email whenever bank or merchant changes
  useEffect(() => {
    setRecipientEmail(guessDeskEmail(bankName, merchantName));
  }, [bankName, merchantName]);

  const handleGenerateLetter = useCallback(async () => {
    setGenerating(true);
    setLetterError('');
    setMailSentNotice('');
    try {
      if (!isDemoMode) {
        const mailRes = await sendDirectMail({
          bankName: bankName || 'Bank',
          merchantName: merchantName || 'Merchant',
          mandateRef: mandateRef || 'REF-DEFAULT',
          recipientEmail: recipientEmail
        });
        setDirectMailData(mailRes);
        setGeneratedLetter(mailRes.body);
      } else {
        await new Promise(r => setTimeout(r, 150));
        const today = new Date().toISOString().split('T')[0];
        const bodyText = `To,
The Branch Manager / Nodal Desk, ${bankName || 'Bank'}

SUBJECT: MANDATE REVOCATION & CANCELLATION NOTICE FOR ${merchantName.toUpperCase()} (REF: ${mandateRef})

Dear Sir/Madam,

I, ${userName}, hereby formally revoke and withdraw consent for the recurring payment mandate 
registered under Reference/Descriptor: ${mandateRef} in favor of ${merchantName} with immediate effect.

In accordance with RBI e-mandate circular instructions on customer opt-out and withdrawal of consent:
1. Please immediately stop and block all future debits associated with this mandate.
2. Acknowledge this revocation in writing or via electronic response with effective date.

Date: ${today}
Sincerely,
${userName}`;
        const subj = `MANDATE REVOCATION NOTICE: ${merchantName.toUpperCase()} [Ref: ${mandateRef}]`;
        const mailto = `mailto:${recipientEmail}?subject=${encodeURIComponent(subj)}&body=${encodeURIComponent(bodyText)}`;
        setGeneratedLetter(bodyText);
        setDirectMailData({
          recipientEmail,
          subject: subj,
          body: bodyText,
          mailtoUri: mailto,
          trackingRef: 'MS-DEMO-' + Math.random().toString(36).substring(2, 8).toUpperCase()
        });
      }
    } catch (err) {
      setLetterError(err.response?.data?.message || 'Could not generate the notice. Check the details and try again.');
    } finally {
      setGenerating(false);
    }
  }, [bankName, isDemoMode, mandateRef, merchantName, recipientEmail, userName]);

  const handleDirectMailSend = async () => {
    if (!directMailData) return;
    setSendingMail(true);
    try {
      // Trigger native mail client directly
      window.location.href = directMailData.mailtoUri;
      setMailSentNotice(`Direct mail launched for ${directMailData.recipientEmail}! Tracking Ref: ${directMailData.trackingRef}`);
    } catch (e) {
      setLetterError('Could not launch mail client. You can copy the notice directly.');
    } finally {
      setSendingMail(false);
    }
  };

  useEffect(() => {
    if (isDemoMode) return;
    getPlaybook(selectedRail, merchantName).then(res => setPlaybookContent([
      `${res.route} cancellation steps:`,
      ...res.paymentSteps.map((step, index) => `${index + 1}. ${step}`),
      ...(res.merchantSteps?.length ? ['', 'Merchant steps:', ...res.merchantSteps.map(step => `• ${step}`)] : []),
      ...(res.ifItContinues?.length ? ['', 'If billing continues:', ...res.ifItContinues.map(step => `• ${step}`)] : []),
    ].join('\n'))).catch(() => setPlaybookContent("1. Open your payment application.\n2. Go to recurring payments.\n3. Choose revoke consent."));
  }, [isDemoMode, merchantName, selectedRail]);

  const copyToClipboard = () => {
    if (!generatedLetter) return;
    navigator.clipboard.writeText(generatedLetter);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const downloadText = () => {
    if (!generatedLetter) return;
    const element = document.createElement("a");
    const file = new Blob([generatedLetter], { type: 'text/plain' });
    element.href = URL.createObjectURL(file);
    element.download = `MandateRevocation_${merchantName.replace(/\s+/g, '_')}.txt`;
    document.body.appendChild(element);
    element.click();
    document.body.removeChild(element);
  };

  const printNotice = () => {
    const printWindow = window.open('', '_blank');
    printWindow.document.write(`
      <html>
        <head>
          <title>Cancellation Request Draft</title>
          <style>
            body { font-family: monospace; white-space: pre-wrap; padding: 40px; line-height: 1.8; }
          </style>
        </head>
        <body>${generatedLetter}</body>
      </html>
    `);
    printWindow.document.close();
    printWindow.print();
  };

  const applyPreset = (preset) => {
    setUserName(preset.userName);
    setBankName(preset.bankName);
    setMerchantName(preset.merchantName);
    setMandateRef(preset.mandateRef);
    setSelectedRail(preset.rail);
    setRecipientEmail(guessDeskEmail(preset.bankName, preset.merchantName));
  };

  return (
    <div className="page-content animate-fade-up">
      {/* Banner */}
      <div className="hero-banner">
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
          <span className="badge badge-primary">Direct Mail Mandate Cancellation</span>
          <span style={{ fontSize: '12px', color: 'var(--color-text-muted)' }}>
            RBI Circular Compliance · Pre-Debit Opt-Out Notice
          </span>
        </div>
        <h2 style={{ fontSize: '22px', fontWeight: 700, color: '#ffffff' }}>
          Direct Mail & Revocation Studio
        </h2>
        <p style={{ color: 'var(--color-text-secondary)', fontSize: '13px', maxWidth: '650px', marginTop: '4px' }}>
          Send direct mail revocation notices to your bank's grievance desk or merchant instead of manual letter copying. Conforms to the RBI 24-48h pre-debit opt-out framework.
        </p>
      </div>

      {/* Payment Rail Playbook Section */}
      <div className="card">
        <div className="card-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <BookOpen size={18} color="var(--color-primary-light)" />
            <span className="card-title">Payment Rail Cancellation Protocol</span>
          </div>

          <div className="tabs">
            {[
              { id: 'UPI_AUTOPAY', label: 'UPI AutoPay' },
              { id: 'NACH', label: 'NACH e-Mandate' },
              { id: 'CREDIT_CARD', label: 'Credit Card SI' },
            ].map(rail => (
              <button
                key={rail.id}
                onClick={() => setSelectedRail(rail.id)}
                className={`tab-btn ${selectedRail === rail.id ? 'active' : ''}`}
              >
                {rail.label}
              </button>
            ))}
          </div>
        </div>

        <div className="card-body">
          {!playbookContent ? (
            <div className="loading-center">
              <div className="spinner"></div>
              <p>Loading rail protocol...</p>
            </div>
          ) : (
            <div style={{
              background: 'var(--color-bg-input)',
              border: '1px solid var(--color-border)',
              borderRadius: 'var(--radius-md)',
              padding: '16px 20px',
              whiteSpace: 'pre-line',
              lineHeight: '1.8',
              color: 'var(--color-text-primary)',
              fontSize: '13px'
            }}>
              {playbookContent}
            </div>
          )}
        </div>
      </div>

      {/* Revocation Notice Generator Form & Output */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))', gap: '24px' }}>
        {/* Form */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">Direct Revocation Parameters</span>
            <span className="badge badge-primary">Direct Mail Mode</span>
          </div>
          <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div className="form-group">
              <label className="form-label">Full Account Holder Name</label>
              <input
                type="text"
                className="form-control"
                value={userName}
                onChange={(e) => setUserName(e.target.value)}
                placeholder="e.g. Dikshaant Sharma"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Bank or Financial Institution</label>
              <input
                type="text"
                className="form-control"
                value={bankName}
                onChange={(e) => setBankName(e.target.value)}
                placeholder="e.g. HDFC Bank, ICICI Bank, SBI"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Merchant / Billing Entity</label>
              <input
                type="text"
                className="form-control"
                value={merchantName}
                onChange={(e) => setMerchantName(e.target.value)}
                placeholder="e.g. Netflix India, Adobe Creative Cloud"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Mandate / URN Reference Number</label>
              <input
                type="text"
                className="form-control form-control-mono"
                value={mandateRef}
                onChange={(e) => setMandateRef(e.target.value)}
                placeholder="e.g. URN-2024-99812-NACH"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Recipient Desk Email (Auto-Detected)</label>
              <input
                type="email"
                className="form-control form-control-mono"
                value={recipientEmail}
                onChange={(e) => setRecipientEmail(e.target.value)}
                placeholder="e.g. nodal.officer@bank.com"
              />
              <small style={{ color: 'var(--color-text-muted)', fontSize: '11px', marginTop: '4px' }}>
                Bank grievance desk / merchant support contact for direct email dispatch.
              </small>
            </div>

            <button
              onClick={handleGenerateLetter}
              disabled={generating}
              className="btn btn-primary"
              style={{ width: '100%', justifyContent: 'center' }}
            >
              <Mail size={16} className={generating ? 'spinner' : ''} />
              <span>{generating ? 'Preparing Direct Mail...' : 'Prepare Direct Mail Notice'}</span>
            </button>

            {/* Presets */}
            <div style={{ marginTop: '8px' }}>
              <label className="form-label" style={{ marginBottom: '8px', display: 'block' }}>
                Quick Test Scenarios (Click to Fill)
              </label>
              <div className="chip-group">
                {SAMPLE_REVOCATION_PRESETS.map((p, idx) => (
                  <button
                    key={idx}
                    onClick={() => applyPreset(p)}
                    className="chip"
                  >
                    <span>{p.merchantName} ({p.rail})</span>
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>

        {/* Legal Notice Preview & Actions */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">Direct Mail Package & Preview</span>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                onClick={copyToClipboard}
                className="btn btn-secondary btn-sm"
                title="Copy to clipboard"
              >
                {copied ? <Check size={14} color="var(--color-success)" /> : <Copy size={14} />}
                <span>{copied ? 'Copied' : 'Copy'}</span>
              </button>

              <button
                onClick={downloadText}
                className="btn btn-secondary btn-sm"
                title="Download as .txt"
              >
                <Download size={14} />
                <span>Download</span>
              </button>

              <button
                onClick={printNotice}
                className="btn btn-secondary btn-sm"
                title="Print or Save as PDF"
              >
                <Printer size={14} />
                <span>Print</span>
              </button>
            </div>
          </div>

          <div className="card-body">
            {letterError && <div className="alert alert-error" role="alert">{letterError}</div>}
            {mailSentNotice && <div className="alert alert-info" role="status">{mailSentNotice}</div>}

            {directMailData && (
              <div style={{
                background: 'rgba(56, 189, 248, 0.08)',
                border: '1px solid rgba(56, 189, 248, 0.25)',
                borderRadius: '8px',
                padding: '12px 16px',
                marginBottom: '16px',
                display: 'flex',
                flexDirection: 'column',
                gap: '8px'
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontSize: '12px', fontWeight: 600, color: '#38bdf8' }}>
                    ✉️ DIRECT MAIL READY FOR DISPATCH
                  </span>
                  <span className="badge badge-primary">{directMailData.trackingRef}</span>
                </div>
                <div style={{ fontSize: '13px', color: 'var(--color-text-secondary)' }}>
                  <strong>To:</strong> {directMailData.recipientEmail}<br/>
                  <strong>Subject:</strong> {directMailData.subject}
                </div>
                <button
                  onClick={handleDirectMailSend}
                  disabled={sendingMail}
                  className="btn btn-primary btn-sm"
                  style={{ alignSelf: 'flex-start', marginTop: '4px' }}
                >
                  <Send size={14} />
                  <span>Send Direct Mail (Open Mail Client)</span>
                  <ExternalLink size={12} />
                </button>
              </div>
            )}

            {generatedLetter ? (
              <div className="letter-box">
                {generatedLetter}
              </div>
            ) : (
              <div className="empty-state">
                <FileText size={32} color="var(--color-text-muted)" />
                <p>Fill in parameters on the left to prepare the Direct Mail notice.</p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
