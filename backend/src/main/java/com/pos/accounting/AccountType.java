package com.pos.accounting;

/**
 * What kind of account it is: decides the statement it appears on, its section there and its
 * normal side. Cash and bank accounts are where money is (sales are paid into them, expenses out).
 */
public enum AccountType {
    CASH(Section.ASSET, "Cash"),
    BANK(Section.ASSET, "Bank"),
    CURRENT_ASSET(Section.ASSET, "Current asset"),
    FIXED_ASSET(Section.ASSET, "Fixed asset"),
    CURRENT_LIABILITY(Section.LIABILITY, "Current liability"),
    LONG_TERM_LIABILITY(Section.LIABILITY, "Long-term liability"),
    EQUITY(Section.EQUITY, "Equity"),
    REVENUE(Section.INCOME, "Revenue"),
    OTHER_INCOME(Section.INCOME, "Other income"),
    COST_OF_SALES(Section.EXPENSE, "Cost of sales"),
    EXPENSE(Section.EXPENSE, "Expense"),
    OTHER_EXPENSE(Section.EXPENSE, "Other expense");

    /** Assets and expenses grow with debits; liabilities, equity and income with credits. */
    public enum Section {
        ASSET(true), LIABILITY(false), EQUITY(false), INCOME(false), EXPENSE(true);

        private final boolean debitNormal;

        Section(boolean debitNormal) {
            this.debitNormal = debitNormal;
        }

        public boolean debitNormal() {
            return debitNormal;
        }

        /** On the profit and loss (rather than the balance sheet). */
        public boolean profitAndLoss() {
            return this == INCOME || this == EXPENSE;
        }
    }

    private final Section section;
    private final String label;

    AccountType(Section section, String label) {
        this.section = section;
        this.label = label;
    }

    public Section section() {
        return section;
    }

    public String label() {
        return label;
    }

    /** Money is kept here: sales, expenses and payments go through it. */
    public boolean money() {
        return this == CASH || this == BANK;
    }

    /** Can be the account of an "other income". */
    public boolean income() {
        return section == Section.INCOME;
    }

    /** Can be the account of an "other expense" or a vendor invoice without an order. */
    public boolean expense() {
        return section == Section.EXPENSE || this == FIXED_ASSET;
    }
}
