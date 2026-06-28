// The 8x8 Othello board, rendered purely from the server's `cells` string. Squares the server
// marked legal are highlighted and clickable; everything else is presentational. No game logic
// here — a click just reports the square index up to the caller.

import type { Player } from './types';

interface BoardProps {
  cells: string;
  legalMoves: number[];
  // Whose turn it is, used only to tint the legal-move hints with the side about to play.
  turn: Player;
  interactive: boolean;
  onPlay: (square: number) => void;
}

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];

export default function Board({
  cells,
  legalMoves,
  turn,
  interactive,
  onPlay,
}: BoardProps) {
  const legal = new Set(legalMoves);

  // Standard orientation: rank 8 on top, file a on the left. Square index is row*8+col with a1=0.
  const ranks = [7, 6, 5, 4, 3, 2, 1, 0];

  return (
    <div className="board-frame">
      <div className="board">
        {ranks.map((row) =>
          FILES.map((file, col) => {
            const square = row * 8 + col;
            const cell = cells[square];
            const playable = interactive && legal.has(square);
            return (
              <button
                key={square}
                type="button"
                className="square"
                aria-label={`${file}${row + 1}`}
                data-square={square}
                disabled={!playable}
                onClick={() => onPlay(square)}
              >
                {cell === 'B' && <span className="disc disc-black" />}
                {cell === 'W' && <span className="disc disc-white" />}
                {cell === '.' && playable && (
                  <span
                    className={`hint hint-${turn.toLowerCase()}`}
                    aria-hidden="true"
                  />
                )}
              </button>
            );
          }),
        )}
      </div>
    </div>
  );
}
