"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { useState } from "react";

import { Button, ErrorNote, Input, Modal } from "@/components/ui";
import { api } from "@/lib/api";
import { useCategories } from "@/lib/queries";
import type { Category } from "@/lib/types";
import { translate, useT } from "@/lib/i18n";

export function CategoriesDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  return (
    <Modal open onClose={onClose} title={t("Categories")}>
      <CategoriesPanel />
    </Modal>
  );
}

/** Categories group the catalog on the sell screen and in reports. */
export function CategoriesPanel() {
  const t = useT();
  const queryClient = useQueryClient();
  const categories = useCategories();
  const [name, setName] = useState("");

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["categories"] });
    queryClient.invalidateQueries({ queryKey: ["offerings"] });
  };
  const create = useMutation({
    mutationFn: () => api<Category>("/categories", { method: "POST", json: { name } }),
    onSuccess: () => {
      setName("");
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (id: number) => api<void>(`/categories/${id}`, { method: "DELETE" }),
    onSuccess: refresh,
  });

  return (
    <div>
      <form
        className="mb-4 flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) create.mutate();
        }}
      >
        <Input placeholder={t("New category")} aria-label={t("New category name")} value={name} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" loading={create.isPending}>
          {t("Add")}
        </Button>
      </form>
      <ErrorNote error={create.error ?? remove.error} />
      <ul className="divide-y divide-border">
        {categories.data?.map((c) => (
          <li key={c.id} className="flex items-center justify-between py-2 text-sm">
            {c.name}
            <button
              onClick={() => {
                if (confirm(translate("Delete “{name}”? Everything in it becomes uncategorised.", { name: c.name }))) remove.mutate(c.id);
              }}
              className="rounded-md p-1.5 text-muted hover:bg-danger-soft hover:text-danger"
              aria-label={t("Delete {name}", { name: c.name })}
            >
              <Trash2 className="size-4" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
