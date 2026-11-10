"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Download, Megaphone, Pencil, Plus, Send, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Badge, Button, ErrorNote, Field, Input, Modal, Select, Spinner, cx } from "@/components/ui";
import { api, download } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { dateTime, money } from "@/lib/format";
import { translate, useT } from "@/lib/i18n";
import { useCurrency } from "@/lib/queries";
import type { Campaign, CampaignRecipient, Promotion, Segment } from "@/lib/types";

import { promotionsKey } from "./PromotionsTab";
import { segmentsKey } from "./SegmentsTab";

export const campaignsKey = ["campaigns"] as const;

const PLACEHOLDERS = ["{name}", "{code}", "{points}", "{store}"];

/**
 * Campaigns: a message to a segment, optionally with a coupon code for each customer. Prepared once,
 * then exported to send with an SMS, Telegram or email tool; results come from the codes used.
 */
export function CampaignsTab() {
  const t = useT();
  const manage = useCan()("CAMPAIGNS_MANAGE");
  const currency = useCurrency();
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: campaignsKey, queryFn: () => api<Campaign[]>("/campaigns") });
  const [editing, setEditing] = useState<Campaign | "new" | null>(null);
  const [viewing, setViewing] = useState<Campaign | null>(null);
  const prepare = useMutation({
    mutationFn: (c: Campaign) => api<Campaign>(`/campaigns/${c.id}/prepare`, { method: "POST" }),
    onSuccess: (c) => {
      queryClient.invalidateQueries({ queryKey: campaignsKey });
      queryClient.invalidateQueries({ queryKey: promotionsKey });
      setViewing(c);
    },
  });
  const remove = useMutation({
    mutationFn: (c: Campaign) => api<void>(`/campaigns/${c.id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: campaignsKey }),
  });

  if (list.isPending) return <Spinner />;
  if (list.error) return <ErrorNote error={list.error} />;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <p className="max-w-2xl text-sm text-muted">
          {t("Prepare a campaign to give each customer their message (and their own code), then download it to send with your SMS, Telegram or email tool.")}
        </p>
        {manage && (
          <Button className="ml-auto" onClick={() => setEditing("new")}>
            <Plus className="size-4" aria-hidden /> {t("New campaign")}
          </Button>
        )}
      </div>
      <ErrorNote error={prepare.error ?? remove.error} />
      {list.data.length === 0 ? (
        <div className="grid place-items-center gap-2 rounded-[20px] border border-dashed border-border px-6 py-14 text-center">
          <Megaphone className="size-9 text-muted" aria-hidden />
          <p className="font-medium">{t("No campaigns yet")}</p>
          <p className="max-w-sm text-sm text-muted">{t("Pick a segment, write a message, and add a coupon so you can see what the campaign brings in.")}</p>
        </div>
      ) : (
        <ul className="grid gap-3 lg:grid-cols-2">
          {list.data.map((c) => (
            <li key={c.id} className="flex flex-col rounded-[20px] border border-border bg-surface p-5">
              <div className="flex items-start gap-3">
                <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary-soft text-primary">
                  <Megaphone className="size-5" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="font-semibold tracking-tight">{c.name}</h3>
                    <Badge tone={c.status === "PREPARED" ? "success" : "neutral"}>{c.status === "PREPARED" ? t("Prepared") : t("Draft")}</Badge>
                  </div>
                  <p className="mt-0.5 text-sm text-muted">
                    {[c.segmentName, c.promotionName && translate("code for {promotion}", { promotion: c.promotionName })].filter(Boolean).join(" · ")}
                  </p>
                  <p className="mt-2 line-clamp-2 rounded-lg bg-surface-2 px-3 py-2 text-sm">{c.message}</p>
                </div>
              </div>
              {c.status === "PREPARED" && (
                <dl className="mt-4 grid grid-cols-4 gap-3 border-t border-border pt-3 text-sm">
                  <Figure label={t("Sent to")} value={c.recipients.toLocaleString()} />
                  <Figure label={t("Codes used")} value={c.promotionId ? `${c.codesUsed}` : "—"} />
                  <Figure label={t("Sales")} value={String(c.sales)} />
                  <Figure label={t("Revenue")} value={money(c.revenue, currency)} />
                </dl>
              )}
              <div className="mt-3 flex flex-wrap gap-2">
                {c.status === "PREPARED" ? (
                  <>
                    {manage && (
                      <>
                        <Button size="sm" variant="secondary" onClick={() => setViewing(c)}>
                          {t("Recipients")}
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => download(`/campaigns/${c.id}/recipients.csv`, `campaign-${c.id}.csv`)}>
                          <Download className="size-4" aria-hidden /> {t("Download CSV")}
                        </Button>
                      </>
                    )}
                    {c.preparedAt && (
                      <span className="self-center text-xs text-muted">
                        {t("Prepared {when} by {name}", { when: dateTime(c.preparedAt), name: c.preparedBy ?? "" })}
                      </span>
                    )}
                  </>
                ) : (
                  manage && (
                    <>
                      <Button
                        size="sm"
                        loading={prepare.isPending && prepare.variables?.id === c.id}
                        onClick={() =>
                          window.confirm(translate("Prepare {name}? Each customer in the segment now gets their message and code, and it can't be changed after.", { name: c.name })) &&
                          prepare.mutate(c)
                        }
                      >
                        <Send className="size-4" aria-hidden /> {t("Prepare")}
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => setEditing(c)}>
                        <Pencil className="size-4" aria-hidden /> {t("Edit")}
                      </Button>
                      <Button size="sm" variant="ghost" className="text-danger" onClick={() => remove.mutate(c)}>
                        <Trash2 className="size-4" aria-hidden /> {t("Delete")}
                      </Button>
                    </>
                  )
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {editing && <CampaignDialog campaign={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      {viewing && <RecipientsDialog campaign={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="font-medium tabular-nums">{value}</dd>
    </div>
  );
}

/** Create or change a draft campaign, with a live preview of the message. */
function CampaignDialog({ campaign, onClose }: { campaign: Campaign | null; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const segments = useQuery({ queryKey: segmentsKey, queryFn: () => api<Segment[]>("/segments") });
  const promotions = useQuery({ queryKey: promotionsKey, queryFn: () => api<Promotion[]>("/promotions") });
  const couponPromotions = (promotions.data ?? []).filter((p) => p.requiresCode && p.active);
  const [name, setName] = useState(campaign?.name ?? "");
  const [segmentId, setSegmentId] = useState(campaign?.segmentId ? String(campaign.segmentId) : "");
  const [promotionId, setPromotionId] = useState(campaign?.promotionId ? String(campaign.promotionId) : "");
  const [message, setMessage] = useState(campaign?.message ?? translate("Hi {name}, thank you for shopping at {store}! Show code {code} for a special discount."));
  const [prefix, setPrefix] = useState(campaign?.codePrefix ?? "");
  const [expires, setExpires] = useState(campaign?.codesExpireOn ?? "");
  const textarea = useRef<HTMLTextAreaElement>(null);

  const previewKey = JSON.stringify({ message, segmentId: segmentId ? Number(segmentId) : null, codePrefix: promotionId ? prefix.trim() || null : null });
  const [settled, setSettled] = useState(previewKey);
  useEffect(() => {
    const id = setTimeout(() => setSettled(previewKey), 300);
    return () => clearTimeout(id);
  }, [previewKey]);
  const preview = useQuery({
    queryKey: [...campaignsKey, "preview", settled],
    enabled: message.trim().length > 0,
    queryFn: () => api<{ message: string; sampleName: string }>("/campaigns/preview-message", { method: "POST", json: JSON.parse(settled) }),
    placeholderData: (previous) => previous,
  });

  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        segmentId: Number(segmentId),
        message: message.trim(),
        promotionId: promotionId ? Number(promotionId) : null,
        codePrefix: prefix.trim() || null,
        codesExpireOn: expires || null,
      };
      return campaign ? api<Campaign>(`/campaigns/${campaign.id}`, { method: "PUT", json: body }) : api<Campaign>("/campaigns", { method: "POST", json: body });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: campaignsKey });
      onClose();
    },
  });

  const insert = (token: string) => {
    const el = textarea.current;
    if (!el) return setMessage((m) => m + token);
    const start = el.selectionStart ?? message.length;
    const end = el.selectionEnd ?? message.length;
    setMessage(message.slice(0, start) + token + message.slice(end));
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(start + token.length, start + token.length);
    });
  };
  const segment = segments.data?.find((s) => String(s.id) === segmentId);

  return (
    <Modal open onClose={onClose} title={campaign ? t("Edit campaign") : t("New campaign")} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label={t("Name")}>
          <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required placeholder={t("e.g. November thank-you")} />
        </Field>
        <Field label={t("Send to")} hint={segment ? t("{n} customers now", { n: segment.members.toLocaleString() }) : undefined}>
          <Select value={segmentId} onChange={(e) => setSegmentId(e.target.value)} required>
            <option value="">{t("Pick a segment")}</option>
            {(segments.data ?? []).map((s) => (
              <option key={s.id} value={s.id}>
                {s.name} ({s.members})
              </option>
            ))}
          </Select>
        </Field>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label={t("Coupon (optional)")} hint={t("Each customer gets a code of their own")}>
            <Select value={promotionId} onChange={(e) => setPromotionId(e.target.value)}>
              <option value="">{t("No coupon")}</option>
              {couponPromotions.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </Select>
          </Field>
          {promotionId && (
            <>
              <Field label={t("Code prefix (optional)")}>
                <Input value={prefix} onChange={(e) => setPrefix(e.target.value.toUpperCase())} maxLength={12} className="font-mono uppercase" placeholder="THANKS" />
              </Field>
              <Field label={t("Codes expire (optional)")}>
                <Input type="date" value={expires} onChange={(e) => setExpires(e.target.value)} />
              </Field>
            </>
          )}
        </div>
        {couponPromotions.length === 0 && (
          <p className="text-xs text-muted">{t("To add a coupon, first create a promotion that needs a coupon code.")}</p>
        )}
        <Field label={t("Message")}>
          <textarea
            ref={textarea}
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            maxLength={1000}
            rows={4}
            required
            className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm focus:outline-2 focus:outline-primary"
          />
        </Field>
        <div className="-mt-2 flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted">{t("Insert")}</span>
          {PLACEHOLDERS.map((p) => (
            <button key={p} type="button" onClick={() => insert(p)} className="rounded-full bg-surface-2 px-2.5 py-0.5 font-mono text-xs ring-1 ring-border hover:bg-primary-soft">
              {p}
            </button>
          ))}
        </div>
        {preview.data && (
          <div className="rounded-xl bg-surface-2 p-3">
            <div className="mb-1 text-xs text-muted">{t("As {name} will get it", { name: preview.data.sampleName })}</div>
            <p className="max-w-md rounded-2xl rounded-bl-sm bg-primary px-3 py-2 text-sm whitespace-pre-line text-on-primary">{preview.data.message}</p>
          </div>
        )}
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {t("Save draft")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** Who a prepared campaign went to, their code and whether they used it. */
function RecipientsDialog({ campaign, onClose }: { campaign: Campaign; onClose: () => void }) {
  const t = useT();
  const list = useQuery({ queryKey: [...campaignsKey, campaign.id, "recipients"], queryFn: () => api<CampaignRecipient[]>(`/campaigns/${campaign.id}/recipients`) });
  return (
    <Modal open onClose={onClose} title={t("Recipients: {name}", { name: campaign.name })} wide>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className="text-sm text-muted">{t("{n} customers", { n: campaign.recipients.toLocaleString() })}</span>
        <Button size="sm" variant="secondary" className="ml-auto" onClick={() => download(`/campaigns/${campaign.id}/recipients.csv`, `campaign-${campaign.id}.csv`)}>
          <Download className="size-4" aria-hidden /> {t("Download CSV")}
        </Button>
      </div>
      {list.isPending ? (
        <Spinner />
      ) : list.error ? (
        <ErrorNote error={list.error} />
      ) : (
        <div className="max-h-[60vh] overflow-y-auto">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-surface text-left text-xs text-muted">
              <tr>
                <th className="py-2 font-medium">{t("Customer")}</th>
                <th className="py-2 font-medium">{t("Contact")}</th>
                <th className="py-2 font-medium">{t("Code")}</th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((r) => (
                <tr key={r.customerId} className="border-t border-border align-top">
                  <td className="py-2">
                    <div className="font-medium">{r.name}</div>
                    <div className="mt-0.5 line-clamp-2 text-xs text-muted">{r.message}</div>
                  </td>
                  <td className="py-2 text-xs">
                    {r.phone && <div className="tabular-nums">{r.phone}</div>}
                    {r.email && <div className="break-all">{r.email}</div>}
                  </td>
                  <td className="py-2 whitespace-nowrap">
                    {r.code ? (
                      <span className={cx("inline-flex items-center gap-1 font-mono text-xs", r.codeUsed && "text-success")}>
                        {r.code}
                        {r.codeUsed && <Check className="size-3.5" aria-label={t("Used")} />}
                      </span>
                    ) : (
                      "—"
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Modal>
  );
}
