import React, { useCallback, useState, useEffect } from 'react';
import { 
  FileText, 
  Copy, 
  Check, 
  Download, 
  Printer, 
  BookOpen,
  Mail,
  ExternalLink,
  ShieldAlert
} from 'lucide-react';
import { getPlaybook, generateLetter } from '../api';
import { SAMPLE_REVOCATION_PRESETS } from '../data/sampleData';

export default function CancellationStudio({ activeUser, isDemoMode, initialData }) {
  // Playbook state
  const [selectedRail, setSelectedRail] = useState(initialData?.rail || 'UPI_AUTOPAY');
  const [playbookContent, setPlaybookContent] = useState('');

  // Notice generator form state
  const [userName, setUserName] = useState(initialData?.userName || activeUser?.name || 'Account Holder');
  const [bankName, setBankName] = useState(initialData?.bankName || 'HDFC Bank');
  const [merchantName, setMerchantName] = useState(initialData?.merchantName || 'Adobe Creative Cloud');
  const [mandateRef, setMandateRef] = useState(initialData?.mandateRef || 'MND-HDFC-99281-ADOBE');
  
  const [generatedLetter, setGeneratedLetter] = useState('');
  const [bankEmail, setBankEmail] = useState('');
  const [emailSubject, setEmailSubject] = useState('');
  const [letterError, setLetterError] = useState('');
  const [generating, setGenerating] = useState(false);
  const [copied, setCopied] = useState(false);
  const [mailSentStatus, setMailSentStatus] = useState(false);

  const handleGenerateLetter = useCallback(async () => {
    setGenerating(true);
    setLetterError('');
    setMailSentStatus(false);
    try {
      if (!isDemoMode) {
        const res = await generateLetter({
          bankName: bankName || 'Bank',
          merchantName: merchantName || 'Merchant',
          mandateRef: mandateRef || 'REF-DEFAULT'
        });
        setGeneratedLetter(res.letter);
        setBankEmail(res.bankEmail || 'nodal.officer@bank.com');
        setEmailSubject(res.emailSubject || `STATUTORY NOTICE: Revocation of Mandate - ${merchantName.toUpperCase()}`);
      } else {
        await new Promise(r => setTimeout(r, 150));
        const today = new Date().toISOString().split('T')[0];
        const letter = `To,
The Nodal Officer / Grievance Redressal Officer / Branch Manager
${bankName || 'Bank'}

SUBJECT: STATUTORY NOTICE OF IMMEDIATE REVOCATION & WITHDRAWAL OF MANDATE / AUTO-DEBIT CONSENT
REFERENCE: RBI Circular RBI/2020-21/68 on e-Mandates & Standing Instructions

Dear Sir/Madam,

I, ${userName}, maintain an account/card facility with your institution.

Pursuant to the Reserve Bank of India (RBI) regulatory framework on Processing of e-Mandates / Standing Instructions / NACH, an account holder retains the unequivocal statutory right to withdraw and revoke consent for recurring debits at any time.

I hereby formally and irrevocably REVOKE, CANCEL, and WITHDRAW my consent for any further debits under the following mandate with IMMEDIATE EFFECT:

• Merchant / Beneficiary Name : ${merchantName.toUpperCase()}
• Mandate Reference / UMN / UMRN: ${mandateRef}
• Payment Facility            : ${bankName} Account / Card
• Revocation Notice Date       : ${today}

Please note that any debit executed by your institution against this mandate subsequent to receipt of this notice shall be deemed unauthorized under RBI guidelines, and you are requested to block all future presentments immediately.

Kindly provide a written acknowledgement confirming this mandate cancellation along with the formal cancellation ticket reference number.

Sincerely,
${userName}`;
        setGeneratedLetter(letter);
        setBankEmail('grievance.redressal@hdfcbank.com');
        setEmailSubject(`STATUTORY NOTICE: Immediate Revocation of Mandate - ${merchantName.toUpperCase()} (Ref: ${mandateRef})`);
      }
    } catch (err) {
      setLetterError(err.response?.data?.message || 'Could not generate the notice. Check the details and try again.');
    } finally {
      setGenerating(false);
    }
  }, [bankName, isDemoMode, mandateRef, merchantName, userName]);

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
    element.download = `RBI_MandateRevocation_${merchantName.replace(/\s+/g, '_')}.txt`;
    document.body.appendChild(element);
    element.click();
    document.body.removeChild(element);
  };

  const printNotice = () => {
    const printWindow = window.open('', '_blank');
    printWindow.document.write(`
      <html>
        <head>
          <title>Statutory Mandate Revocation Notice</title>
          <style>
            body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; white-space: pre-wrap; padding: 40px; line-height: 1.8; color: #111; }
          </style>
        </head>
        <body>${generatedLetter}</body>
      </html>
    `);
    printWindow.document.close();
    printWindow.print();
  };

  // Direct 1-Click Mailto & Webmail Dispatcher to Bank Nodal Officer
  const sendEmailToBank = () => {
    if (!generatedLetter) return;
    const recipient = bankEmail || 'nodal.officer@bank.com';
    const subject = emailSubject || `Statutory Mandate Revocation Notice: ${merchantName}`;
    const body = generatedLetter;

    // Build mailto link
    const mailtoUrl = `mailto:${recipient}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;

    // Try opening mailto via invisible anchor click (bypasses browser navigation locks)
    const link = document.createElement('a');
    link.href = mailtoUrl;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setMailSentStatus(true);
  };

  const openGmailWeb = () => {
    if (!generatedLetter) return;
    const recipient = bankEmail || 'nodal.officer@bank.com';
    const subject = emailSubject || `Statutory Mandate Revocation Notice: ${merchantName}`;
    const body = generatedLetter;
    const gmailUrl = `https://mail.google.com/mail/?view=cm&fs=1&to=${encodeURIComponent(recipient)}&su=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
    window.open(gmailUrl, '_blank', 'noopener,noreferrer');
  };

  const applyPreset = (preset) => {
    setUserName(preset.userName);
    setBankName(preset.bankName);
    setMerchantName(preset.merchantName);
    setMandateRef(preset.mandateRef);
    setSelectedRail(preset.rail);
  };

  return (
    <div className="page-content animate-fade-up">
      {/* Banner */}
      <div className="hero-banner">
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
          <span className="badge badge-primary">Statutory Bank Revocation</span>
          <span style={{ fontSize: '12px', color: 'var(--color-text-muted)' }}>
            RBI Circular RBI/2020-21/68 Mandate Revocation Framework
          </span>
        </div>
        <h2 style={{ fontSize: '22px', fontWeight: 700, color: '#ffffff' }}>
          1-Click Bank Mandate Revocation Studio
        </h2>
        <p style={{ color: 'var(--color-text-secondary)', fontSize: '13px', maxWidth: '680px', marginTop: '4px' }}>
          Exercise your statutory right under RBI regulations to stop unauthorized auto-debits.
          Generate a legally binding revocation notice and <strong>dispatch it directly to your bank's grievance officer in 1 click</strong>.
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
            <span className="card-title">Mandate & Bank Parameters</span>
            <span className="badge badge-primary">Statutory notice</span>
          </div>
          <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div className="form-group">
              <label className="form-label">Account Holder Full Name</label>
              <input
                type="text"
                className="form-control"
                value={userName}
                onChange={(e) => setUserName(e.target.value)}
                placeholder="e.g. Dikshaant Sharma"
              />
            </div>

            <div className="form-group">
              <label className="form-label">Bank or Card Issuer Name</label>
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
              <label className="form-label">Mandate Reference / UMN / UMRN</label>
              <input
                type="text"
                className="form-control form-control-mono"
                value={mandateRef}
                onChange={(e) => setMandateRef(e.target.value)}
                placeholder="e.g. URN-2024-99812-NACH"
              />
            </div>

            <button
              onClick={handleGenerateLetter}
              disabled={generating}
              className="btn btn-primary"
              style={{ width: '100%', justifyContent: 'center' }}
            >
              <FileText size={16} className={generating ? 'spinner' : ''} />
              <span>{generating ? 'Drafting Statutory Notice...' : 'Generate Legal Revocation Notice'}</span>
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
            <span className="card-title">Legal Revocation Notice</span>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                onClick={copyToClipboard}
                className="btn btn-secondary btn-sm"
                title="Copy to clipboard"
              >
                {copied ? <Check size={14} color="var(--color-success)" /> : <Copy size={14} />}
                <span>{copied ? 'Copied!' : 'Copy'}</span>
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
            
            {generatedLetter ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                {/* 1-Click Send Email Action Box */}
                <div style={{
                  background: 'rgba(59, 130, 246, 0.08)',
                  border: '1px solid rgba(59, 130, 246, 0.3)',
                  borderRadius: 'var(--radius-md)',
                  padding: '16px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '12px'
                }}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <Mail size={18} color="var(--color-primary-light)" />
                      <strong style={{ fontSize: '14px', color: '#fff' }}>Direct 1-Click Bank Email Dispatch</strong>
                    </div>
                    <span className="badge badge-success">Official Grievance Email</span>
                  </div>

                  <div style={{ fontSize: '12px', color: 'var(--color-text-secondary)', display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '6px 12px' }}>
                    <strong>Bank Officer:</strong> <span>{bankEmail || 'nodal.officer@bank.com'}</span>
                    <strong>Legal Ref:</strong> <span>RBI Circular RBI/2020-21/68 Statutory Right</span>
                  </div>

                  <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                    <button
                      onClick={sendEmailToBank}
                      className="btn btn-primary"
                      style={{ flex: 1, minWidth: '220px', justifyContent: 'center', padding: '10px 16px', fontSize: '13px', fontWeight: 600 }}
                    >
                      <Mail size={16} />
                      <span>Open Mail App (Outlook / Apple Mail)</span>
                    </button>
                    <button
                      type="button"
                      onClick={openGmailWeb}
                      className="btn btn-secondary"
                      style={{ minWidth: '180px', justifyContent: 'center', padding: '10px 16px', fontSize: '13px', fontWeight: 600, background: '#ea4335', color: '#fff', borderColor: '#ea4335' }}
                    >
                      <ExternalLink size={15} />
                      <span>Open in Gmail (Browser)</span>
                    </button>
                  </div>

                  {mailSentStatus && (
                    <div style={{ fontSize: '12px', color: 'var(--color-success)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                      <Check size={14} />
                      <span>Dispatch triggered! If your default mail app isn't configured, click "Open in Gmail" above.</span>
                    </div>
                  )}
                </div>

                <div className="letter-box">
                  {generatedLetter}
                </div>
              </div>
            ) : (
              <div className="empty-state">
                <FileText size={32} color="var(--color-text-muted)" />
                <p>Fill in parameters on the left to generate the statutory revocation notice and email dispatcher.</p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
