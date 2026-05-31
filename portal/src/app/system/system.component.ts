import { Component } from '@angular/core';

@Component({
  selector: 'app-system',
  template: `
    <section class="system-wip">
      <h2>System</h2>
      <p>Work in progress — observability metrics will live here.</p>
    </section>
  `,
  styles: [
    `
      .system-wip {
        padding: 2rem;
        color: var(--gray-700, #555);
      }
    `,
  ],
})
export class SystemComponent {}
