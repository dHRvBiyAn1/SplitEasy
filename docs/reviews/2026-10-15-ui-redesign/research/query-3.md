## UI Pro Max Search Results
**Domain:** gsap | **Query:** scroll reveal stagger
**Source:** motion.csv | **Found:** 2 results

### Result 1
- **Category:** Stagger List
- **Intensity Tier:** Complex
- **Trigger:** load or scroll
- **Duration:** 400-700ms
- **Easing:** expo.out
- **GSAP Snippet:** const split = new SplitText(headline, { type: 'chars' }); gsap.from(split.chars, { opacity: 0, y: 20, rotateX: -40, duration: 0.6, stagger: 0.015, ease: 'expo.out' });
- **Framework Notes:** SplitText is included with GSAP 3.13+; register it before use, review the current GSAP license, and keep a plain-text fade fallback; Use gsap.matchMedia('(prefers-reduced-motion: reduce)') to skip character motion and render the readable final state immediately
- **Do:** Revert SplitText on unmount/cleanup (split.revert()) to restore original text nodes for accessibility tools
- **Don't:** Don't split-animate long paragraphs; reserve for short headlines (under ~8 words)
- **Performance Notes:** Splitting text creates one element per character; keep it to headline-length copy only for DOM size

### Result 2
- **Category:** Scroll Reveal
- **Intensity Tier:** Subtle
- **Trigger:** scroll (viewport enter)
- **Duration:** 300-400ms
- **Easing:** power1.out
- **GSAP Snippet:** gsap.from(el, { opacity: 0, y: 12, duration: 0.35, ease: 'power1.out', scrollTrigger: { trigger: el, start: 'top 90%', toggleActions: 'play none none reverse' } });
- **Framework Notes:** Requires the ScrollTrigger plugin registered once via gsap.registerPlugin(ScrollTrigger); Use matchMedia('(prefers-reduced-motion: reduce)') to skip non-essential motion and render the final state immediately
- **Do:** Keep the y offset small (8-16px) so it reads as a fade, not a slide
- **Don't:** Don't reveal below-the-fold content needed for SEO/crawlers as invisible-by-default without a no-JS fallback
- **Performance Notes:** toggleActions 'play none none reverse' avoids re-triggering on every scroll direction change
